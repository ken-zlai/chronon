package ai.chronon.quickstart.online

import ai.chronon.online.{
  Api,
  ExternalSourceRegistry,
  GroupByServingInfoParsed,
  KVStore,
  LoggableResponse,
  StreamDecoder
}

import org.mongodb.scala._

import jnr.ffi.annotations.Transient
import org.slf4j.{Logger, LoggerFactory}

class ChrononMongoOnlineImpl(userConf: Map[String, String]) extends Api(userConf) {

  @transient lazy val registry: ExternalSourceRegistry = new ExternalSourceRegistry()

  @Transient val logger: Logger = LoggerFactory.getLogger("ChrononMongoOnlineImpl")

  @transient lazy val mongoClient = MongoClient(s"mongodb://${userConf("user")}:${userConf("password")}@${userConf("host")}:${userConf("port")}")
  override def streamDecoder(groupByServingInfoParsed: GroupByServingInfoParsed): StreamDecoder = ???

  override def genKvStore: KVStore = new MongoKvStore(mongoClient, userConf("database"))


  @transient lazy val loggingClient = mongoClient.getDatabase(userConf("database")).getCollection("chronon_logging")
  override def logResponse(resp: LoggableResponse): Unit =
    loggingClient.insertOne(Document(
      "joinName" -> resp.joinName,
      "keyBytes" -> resp.keyBytes,
      "schemaHash" -> resp.schemaHash,
      "valueBytes" -> resp.valueBytes,
      "atMillis" -> resp.tsMillis,
      "ts" -> System.currentTimeMillis(),
    )).toFuture()

  override def externalRegistry: ExternalSourceRegistry = registry
}