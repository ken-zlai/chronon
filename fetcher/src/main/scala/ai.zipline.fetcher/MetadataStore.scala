package ai.zipline.fetcher

import ai.zipline.api.Constants.ZiplineMetadataKey
import ai.zipline.api.Extensions.{JoinOps, StringOps}
import ai.zipline.api.KVStore.PutRequest
import ai.zipline.api._
import org.apache.thrift.TBase
import org.rogach.scallop._
import scala.concurrent.duration._
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.{Option => JsonPathOption}

import java.io.File

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag

class MetadataStore(kvStore: KVStore, val dataset: String = ZiplineMetadataKey, timeoutMillis: Long) {
  implicit val executionContext: ExecutionContext = kvStore.executionContext

  lazy val getJoinConf: TTLCache[String, JoinOps] = new TTLCache[String, JoinOps]({ name =>
    val startTimeMs = System.currentTimeMillis()
    val joinOps: JoinOps = new JoinOps(
      ThriftJsonCodec
        .fromJsonStr[Join](kvStore.getString(s"joins/$name", dataset, timeoutMillis), check = true, classOf[Join]))
    MetadataMetrics.reportJoinConfRequestMetric(System.currentTimeMillis() - startTimeMs, Metrics.Context(join = name))
    joinOps
  })

  def putJoinConf(join: Join): Unit = {
    kvStore.put(
      PutRequest(s"joins/${join.metaData.name}".getBytes(Constants.UTF8),
                 ThriftJsonCodec.toJsonStr(join).getBytes(Constants.UTF8),
                 dataset))
  }

  lazy val getGroupByServingInfo: TTLCache[String, GroupByServingInfoParsed] =
    new TTLCache[String, GroupByServingInfoParsed]({ name =>
      val startTimeMs = System.currentTimeMillis()
      val batchDataset = s"${name.sanitize.toUpperCase()}_BATCH"
      val metaData =
        kvStore.getString(Constants.GroupByServingInfoKey, batchDataset, timeoutMillis)
      println(s"Fetched ${Constants.GroupByServingInfoKey} from : $batchDataset\n$metaData")
      val groupByServingInfo = ThriftJsonCodec
        .fromJsonStr[GroupByServingInfo](metaData, check = true, classOf[GroupByServingInfo])
      MetadataMetrics.reportJoinConfRequestMetric(
        System.currentTimeMillis() - startTimeMs,
        Metrics.Context(groupBy = groupByServingInfo.getGroupBy.getMetaData.getName))
      new GroupByServingInfoParsed(groupByServingInfo)
    })

  // upload the materialized JSONs to KV store:
  // key = <conf_type>/<team>/<conf_name> in bytes e.g joins/team/team.example_join.v1 value = materialized json string in bytes
  def putConf(configPath: String): Future[Seq[Boolean]] = {
    val configFile = new File(configPath)
    assert(configFile.exists(), s"$configFile does not exist")
    println(s"Uploading Zipline configs from $configPath")
    val fileList = listFiles(configFile)

    val configuration = Configuration.builder.options(JsonPathOption.SUPPRESS_EXCEPTIONS).build
    val puts = fileList
      .filter(
        // the current Zipline config should have metaData.name field
        // if this field doesn't exist, we will simply skip for further parsing validation
        JsonPath.parse(_, configuration).read("$.metaData.name") != null
      )
      .flatMap { file =>
        val path = file.getPath
        // capture <conf_type>/<team>/<conf_name> as key e.g joins/team/team.example_join.v1
        val name: String = JsonPath.parse(file, configuration).read("$.metaData.name")
        val (key, confJsonOpt) = path match {
          case value if value.contains("staging_queries/") => (s"staging_queries/$name", loadJson[StagingQuery](value))
          case value if value.contains("joins/")           => (s"joins/$name", loadJson[Join](value))
          case value if value.contains("group_bys/")       => (s"group_bys/$name", loadJson[GroupBy](value))
          case _                                           => println(s"unknown config type in file $path"); ("", None)
        }
        confJsonOpt
          .map(conf =>
            PutRequest(keyBytes = key.getBytes(),
                       valueBytes = conf.getBytes(),
                       dataset = dataset,
                       tsMillis = Some(System.currentTimeMillis())))
      }
    println(s"Putting ${puts.size} configs to KV Store, dataset=$dataset")
    kvStore.multiPut(puts)
  }

  // list file recursively
  private def listFiles(base: File, recursive: Boolean = true): Seq[File] = {
    if (base.isFile) {
      Seq(base)
    } else {
      val files = base.listFiles
      val result = files.filter(_.isFile)
      result ++
        files
          .filter(_.isDirectory)
          .filter(_ => recursive)
          .flatMap(listFiles(_, recursive))
    }
  }

  // process zipline configs only. others will be ignored
  // todo: add metrics
  private def loadJson[T <: TBase[_, _]: Manifest: ClassTag](file: String): Option[String] = {
    try {
      val configConf = ThriftJsonCodec.fromJsonFile[T](file, check = true)
      Some(ThriftJsonCodec.toJsonStr(configConf))
    } catch {
      case _: Throwable =>
        println(s"Failed to parse JSON to Zipline configs, file path = $file")
        None
    }
  }
}

object MetadataUploader {
  class Args(arguments: Seq[String]) extends ScallopConf(arguments) {
    val confPath: ScallopOption[String] =
      opt[String](required = true, descr = "Path to the Zipline config file or directory")
    verify()
  }

  def run(args: Args, onlineImpl: OnlineImpl): Unit = {
    val metadataStore = new MetadataStore(onlineImpl.genKvStore, "ZIPLINE_METADATA", timeoutMillis = 10000)
    val putRequest = metadataStore.putConf(args.confPath())
    val res = Await.result(putRequest, 1.hour)
    println(
      s"Uploaded Zipline Configs to Mussel, success count = ${res.count(v => v)}, failure count = ${res.count(!_)}")
  }
}
