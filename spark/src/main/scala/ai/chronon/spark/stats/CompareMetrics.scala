package ai.chronon.spark.stats

import ai.chronon.aggregator.row.RowAggregator
import ai.chronon.api.Extensions.{AggregationPartOps, WindowUtils}
import ai.chronon.api._
import ai.chronon.online.DataMetrics
import ai.chronon.spark.{Comparison, Conversions}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.GenericRow
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.{Column, DataFrame, functions, types, Row => SparkRow}

import java.util
import scala.collection.immutable.SortedMap

object CompareMetrics {
  val leftSuffix = "_left"
  val rightSuffix = "_right"
  val comparisonViewNameSuffix = "_comparison"

  case class MetricTransform(name: String,
                             expr: Column,
                             operation: Operation,
                             argMap: util.Map[String, String] = null,
                             additionalExprs: Seq[(String, String)] = null)

  private def edit_distance: UserDefinedFunction =
    functions.udf((left: Object, right: Object) => EditDistance.between(left, right))

  def buildMetrics(valueFields: Array[StructField]): Seq[MetricTransform] =
    valueFields.flatMap { field =>
      val left = functions.col(field.name + leftSuffix)
      val right = functions.col(field.name + rightSuffix)
      val universalMetrics = Seq(
        MetricTransform("both_null", left.isNull.and(right.isNull), Operation.SUM),
        MetricTransform("left_null", left.isNull.and(right.isNotNull), Operation.SUM),
        MetricTransform("right_null", left.isNotNull.and(right.isNull), Operation.SUM)
      )
      val smape_denom = functions.abs(left) + functions.abs(right)
      val numericMetrics = Seq(
        MetricTransform(
          "smape",
          functions
            .when(
              smape_denom.notEqual(0.0),
              (functions.abs(left - right) * 2).cast(types.DoubleType) / smape_denom
            )
            .otherwise(0.0),
          Operation.AVERAGE
        ),
        MetricTransform("left_minus_right", left - right, Operation.APPROX_PERCENTILE),
        MetricTransform("left", left, Operation.APPROX_PERCENTILE),
        MetricTransform("right", right, Operation.APPROX_PERCENTILE)
      )

      val sequenceMetrics = Seq(
        MetricTransform(
          "edit_distance",
          edit_distance(left, right),
          Operation.APPROX_PERCENTILE,
          additionalExprs = Seq(
            "insert" -> ".insert",
            "delete" -> ".delete"
          )
        ),
        MetricTransform("left_length", functions.size(left), Operation.APPROX_PERCENTILE),
        MetricTransform("right_length", functions.size(right), Operation.APPROX_PERCENTILE)
      )

      val equalityMetric =
        if (!DataType.isMap(field.fieldType))
          Some(
            MetricTransform("mismatch",
                            left.isNotNull.and(right.isNotNull).and(left.notEqual(right)),
                            Operation.SUM))
        else None

      val typeSpecificMetrics = if (DataType.isNumeric(field.fieldType)) {
        numericMetrics
      } else if (DataType.isList(field.fieldType)) {
        sequenceMetrics
      } else {
        Seq.empty[MetricTransform]
      }

      val allMetrics = (universalMetrics ++ typeSpecificMetrics ++ equalityMetric)
        .map { m =>
          val fullName = field.name + "_" + m.name
          m.copy(
            name = fullName,
            expr = m.expr.as(fullName),
            additionalExprs = Option(m.additionalExprs)
              .map(_.map { case (name, expr) => (fullName + "_" + name, fullName + expr) })
              .orNull
          )
        }
      allMetrics
    }

  def buildRowAggregator(metrics: Seq[MetricTransform], inputDf: DataFrame): RowAggregator = {
    val schema = Conversions.toChrononSchema(inputDf.schema)
    val aggParts = metrics.flatMap { m =>
      def buildAggPart(name: String): AggregationPart = {
        val aggPart = new AggregationPart()
        aggPart.setInputColumn(name)
        aggPart.setOperation(m.operation)
        if (m.argMap != null)
          aggPart.setArgMap(m.argMap)
        aggPart.setWindow(WindowUtils.Unbounded)
        aggPart
      }
      if (m.additionalExprs == null) {
        Seq(buildAggPart(m.name))
      } else {
        m.additionalExprs.map { case (name, _) => buildAggPart(name) }
      }
    }
    new RowAggregator(schema, aggParts)
  }

  def compute(valueFields: Array[StructField],
              inputDf: DataFrame,
              timeBucketMinutes: Long = 60): (DataFrame, DataMetrics) = {
    // spark maps cannot be directly compared, for now we compare the string representation
    // TODO 1: For Maps, we should find missing keys, extra keys and mismatched keys
    // TODO 2: Values should have type specific comparison
    val valueSchema = valueFields.map(field =>
      field.fieldType match {
        case MapType(_, _) => StructField(field.name, StringType)
        case _             => field
      })
    val metrics = buildMetrics(valueSchema)
    val selectedDf = Comparison
      .stringifyMaps(inputDf)
      .select(metrics.map(_.expr) :+ functions.col(Constants.TimeColumn): _*)
    val secondPassSelects = metrics.flatMap { metric =>
      if (metric.additionalExprs != null) {
        metric.additionalExprs.map { case (name, expr) => (s"$expr as $name") }
      } else {
        Seq(metric.name)
      }
    }
    // TODO: We are currently bucketing based on the time but we should extend it to support other bucketing strategy.
    val secondPassDf = selectedDf.selectExpr(secondPassSelects :+ Constants.TimeColumn: _*)
    val rowAggregator = buildRowAggregator(metrics, secondPassDf)
    val bucketMs = 1000 * 60 * timeBucketMinutes
    val tsIndex = secondPassDf.schema.fieldIndex(Constants.TimeColumn)
    val outputColumns = rowAggregator.aggregationParts.map(_.outputColumnName).toArray
    def sortedMap(vals: Seq[(String, Any)]) = SortedMap.empty[String, Any] ++ vals
    val resultRdd = secondPassDf.rdd
      .keyBy(row => (row.getLong(tsIndex) / bucketMs) * bucketMs) // bin
      .mapValues(Conversions.toChrononRow(_, -1))
      .aggregateByKey(rowAggregator.init)(rowAggregator.updateWithReturn, rowAggregator.merge) // aggregate
      .mapValues(rowAggregator.finalize)

    val resultRowRdd: RDD[SparkRow] = resultRdd.map {
      case (bucketStart, metrics) => new GenericRow(bucketStart +: metrics)
    }
    val resultChrononSchema = StructType.from("ooc_metrics", ("ts", LongType) +: rowAggregator.outputSchema)
    val resultSparkSchema = Conversions.fromChrononSchema(resultChrononSchema)
    val resultDf = inputDf.sparkSession.createDataFrame(resultRowRdd, resultSparkSchema)

    val result = resultRdd
      .collect()
      .sortBy(_._1)
      .map { case (bucketStart, vals) => bucketStart -> sortedMap(outputColumns.zip(vals)) }
    (resultDf -> DataMetrics(result))
  }
}
