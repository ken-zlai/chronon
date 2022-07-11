package ai.chronon.spark.streaming

import ai.chronon
import ai.chronon.api
import ai.chronon.api.Extensions.{GroupByOps, SourceOps}
import ai.chronon.api.{Row => _, _}
import ai.chronon.online._
import ai.chronon.spark.Conversions
import com.google.gson.Gson
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.streaming.{DataStreamWriter, StreamingQuery, Trigger}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneId, ZoneOffset}
import java.util.Base64
import scala.collection.JavaConverters._

class GroupBy(inputStream: DataFrame,
              session: SparkSession,
              groupByConf: api.GroupBy,
              onlineImpl: Api,
              debug: Boolean = false)
    extends Serializable {

  private def buildStreamingQuery(): String = {
    val streamingSource = groupByConf.streamingSource.get
    val query = streamingSource.query
    val selects = Option(query.selects).map(_.asScala.toMap).orNull
    val timeColumn = Option(query.timeColumn).getOrElse(Constants.TimeColumn)
    val fillIfAbsent = groupByConf.dataModel match {
      case DataModel.Entities =>
        Map(Constants.TimeColumn -> timeColumn, Constants.ReversalColumn -> null, Constants.MutationTimeColumn -> null)
      case chronon.api.DataModel.Events => Map(Constants.TimeColumn -> timeColumn)
    }
    val keys = groupByConf.getKeyColumns.asScala

    val baseWheres = Option(query.wheres).map(_.asScala).getOrElse(Seq.empty[String])
    val selectMap = Option(selects).getOrElse(Map.empty[String, String])
    val keyWhereOption = keys
      .map { key =>
        s"${selectMap.getOrElse(key, key)} IS NOT NULL"
      }
      .mkString(" OR ")
    val timeWheres = groupByConf.dataModel match {
      case chronon.api.DataModel.Entities => Seq(s"${Constants.MutationTimeColumn} is NOT NULL")
      case chronon.api.DataModel.Events   => Seq(s"$timeColumn is NOT NULL")
    }
    QueryUtils.build(
      selects,
      Constants.StreamingInputTable,
      baseWheres ++ timeWheres :+ s"($keyWhereOption)",
      fillIfAbsent = if (selects == null) null else fillIfAbsent
    )
  }

  def run(local: Boolean = false): StreamingQuery = {
    buildDataStream(local).start()
  }

  def buildDataStream(local: Boolean = false): DataStreamWriter[KVStore.PutRequest] = {
    val kvStore = onlineImpl.genKvStore
    val fetcher = new Fetcher(kvStore)
    val groupByServingInfo = if (local) {
      // don't talk to kv store - instead make a dummy ServingInfo
      val gb = new GroupByServingInfo()
      gb.setGroupBy(groupByConf)
      new GroupByServingInfoParsed(gb)
    } else {
      fetcher.getGroupByServingInfo(groupByConf.getMetaData.getName).get
    }

    val streamDecoder = onlineImpl.streamDecoder(groupByServingInfo)
    assert(groupByConf.streamingSource.isDefined,
           "No streaming source defined in GroupBy. Please set a topic/mutationTopic.")
    val streamingSource = groupByConf.streamingSource.get
    val streamingQuery = buildStreamingQuery()

    val context = Metrics.Context(Metrics.Environment.GroupByStreaming, groupByConf)
    val ingressContext = context.withSuffix("ingress")
    import session.implicits._
    implicit val structTypeEncoder: Encoder[Mutation] = Encoders.kryo[Mutation]
    val deserialized: Dataset[Mutation] = inputStream
      .as[Array[Byte]]
      .map { arr =>
        ingressContext.increment(Metrics.Name.RowCount)
        ingressContext.count(Metrics.Name.Bytes, arr.length)
        streamDecoder.decode(arr)
      }
      .filter(mutation =>
        !(mutation.before != null && mutation.after != null) || !(mutation.before sameElements mutation.after))

    val streamSchema = Conversions.fromChrononSchema(streamDecoder.schema)
    println(s"""
        | group by serving info: $groupByServingInfo
        | Streaming source: $streamingSource
        | streaming Query: $streamingQuery
        | streaming dataset: ${groupByConf.streamingDataset}
        | stream schema: $streamSchema
        |""".stripMargin)

    val des = deserialized
      .flatMap { mutation =>
        Seq(mutation.after, mutation.before)
          .filter(_ != null)
          .map(Conversions.toSparkRow(_, streamDecoder.schema).asInstanceOf[Row])
      }(RowEncoder(streamSchema))

    des.createOrReplaceTempView(Constants.StreamingInputTable)

    groupByConf.setups.foreach(session.sql)
    val selectedDf = session.sql(streamingQuery)
    assert(selectedDf.schema.fieldNames.contains(Constants.TimeColumn),
           s"time column ${Constants.TimeColumn} must be included in the selects")
    if (groupByConf.dataModel == chronon.api.DataModel.Entities) {
      assert(selectedDf.schema.fieldNames.contains(Constants.MutationTimeColumn), "Required Mutation ts")
    }
    val keys = groupByConf.keyColumns.asScala.toArray
    val keyIndices = keys.map(selectedDf.schema.fieldIndex)
    val (additionalColumns, eventTimeColumn) = groupByConf.dataModel match {
      case chronon.api.DataModel.Entities => groupByServingInfo.MutationAvroColumns -> Constants.MutationTimeColumn
      case chronon.api.DataModel.Events   => Seq.empty[String] -> Constants.TimeColumn
    }
    val valueColumns = groupByConf.aggregationInputs ++ additionalColumns
    val valueIndices = valueColumns.map(selectedDf.schema.fieldIndex)
    val tsIndex = selectedDf.schema.fieldIndex(eventTimeColumn)
    val streamingDataset = groupByConf.streamingDataset

    def schema(indices: Seq[Int], name: String): AvroCodec = {
      val fields = indices
        .map(Conversions.toChrononSchema(selectedDf.schema))
        .map { case (f, d) => StructField(f, d) }
        .toArray
      AvroCodec.of(AvroConversions.fromChrononSchema(StructType(name, fields)).toString())
    }
    val keyCodec = schema(keyIndices, "key")
    val valueCodec = schema(valueIndices, "selected")
    val dataWriter = new DataWriter(onlineImpl, context.withSuffix("egress"), 120, debug)
    selectedDf
      .map { row =>
        val keys = keyIndices.map(row.get)
        val values = valueIndices.map(row.get)

        val ts = row.get(tsIndex).asInstanceOf[Long]
        val keyBytes = keyCodec.encodeArray(keys)
        val valueBytes = valueCodec.encodeArray(values)
        if (debug) {
          val gson = new Gson()
          val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.from(ZoneOffset.UTC))
          val pstFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.of("America/Los_Angeles"))
          println(s"""
               |keys: ${gson.toJson(keys)}
               |values: ${gson.toJson(values)}
               |keyBytes: ${Base64.getEncoder.encodeToString(keyBytes)}
               |valueBytes: ${Base64.getEncoder.encodeToString(valueBytes)}
               |ts: $ts  |  UTC: ${formatter.format(Instant.ofEpochMilli(ts))} | PST: ${pstFormatter.format(
            Instant.ofEpochMilli(ts))}
               |""".stripMargin)
        }
        KVStore.PutRequest(keyBytes, valueBytes, streamingDataset, Option(ts))
      }
      .writeStream
      .outputMode("append")
      .trigger(Trigger.Continuous("60 second"))
      .foreach(dataWriter)
  }
}