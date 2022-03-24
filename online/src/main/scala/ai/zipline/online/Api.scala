package ai.zipline.online

import ai.zipline.api.{Constants, StructType}
import ai.zipline.online.KVStore.{GetRequest, GetResponse, PutRequest}

import java.util.concurrent.Executors
import scala.concurrent.duration.{Duration, MILLISECONDS}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object KVStore {
  // a scan request essentially for the keyBytes
  // afterTsMillis - is used to limit the scan to more recent data
  case class GetRequest(keyBytes: Array[Byte], dataset: String, afterTsMillis: Option[Long] = None)
  case class TimedValue(bytes: Array[Byte], millis: Long)
  case class GetResponse(request: GetRequest, values: Try[Seq[TimedValue]]) {
    def latest: Try[TimedValue] = values.map(_.maxBy(_.millis))
  }
  case class PutRequest(keyBytes: Array[Byte], valueBytes: Array[Byte], dataset: String, tsMillis: Option[Long] = None)
}

// the main system level api for key value storage
// used for streaming writes, batch bulk uploads & fetching
trait KVStore {
  implicit val executionContext: ExecutionContext = ExecutionContext.fromExecutor(Executors.newWorkStealingPool())

  def create(dataset: String): Unit
  def multiGet(requests: Seq[GetRequest]): Future[Seq[GetResponse]]
  def multiPut(keyValueDatasets: Seq[PutRequest]): Future[Seq[Boolean]]

  def bulkPut(sourceOfflineTable: String, destinationOnlineDataSet: String, partition: String): Unit

  // helper methods to do single put and single get
  def get(request: GetRequest): Future[Option[GetResponse]] = {
    val result = multiGet(Seq(request)).map(_.headOption)
    result
  }
  def put(putRequest: PutRequest): Future[Boolean] = multiPut(Seq(putRequest)).map(_.head)

  // helper method to blocking read a string - used for fetching metadata & not in hotpath.
  def getString(key: String, dataset: String, timeoutMillis: Long): Try[String] = {
    val fetchRequest = KVStore.GetRequest(key.getBytes(Constants.UTF8), dataset)
    val responseFutureOpt = get(fetchRequest)
    val responseOpt = Await.result(responseFutureOpt, Duration(timeoutMillis, MILLISECONDS))
    if (responseOpt.isEmpty) {
      println(s"zipline Request for key ${key} in dataset ${dataset} is empty")
      Failure(new RuntimeException(s"Request for key ${key} in dataset ${dataset} is empty"))
    } else if (responseOpt.get.values.isFailure) {
      println(s"zipline Request for key ${key} in dataset ${dataset} failed", responseOpt.get.values.failed.get)
      Failure(
        new RuntimeException(s"Request for key ${key} in dataset ${dataset} failed", responseOpt.get.values.failed.get))
    } else {
      println(s"zipline metadata ${new String(responseOpt.get.latest.get.bytes, Constants.UTF8)}")
      Success(new String(responseOpt.get.latest.get.bytes, Constants.UTF8))
    }
  }
}

/**
  * ==== MUTATION vs. EVENT ====
  * Mutation is the general case of an Event
  * Imagine a user impression/view stream - impressions/views are immutable events
  * Imagine a stream of changes to a credit card transaction stream.
  *    - transactions can be "corrected"/updated & deleted, besides being "inserted"
  *    - This is one of the core difference between entity and event sources. Events are insert-only.
  *    - (The other difference is Entites are stored in the warehouse typically as snapshots of the table as of midnight)
  * In case of an update - one must produce both before and after values
  * In case of a delete - only before is populated & after is left as null
  * In case of a insert - only after is populated & before is left as null

  * ==== TIME ASSUMPTIONS ====
  * The schema needs to contain a `ts`(milliseconds as a java Long)
  * For the entities case, `mutation_ts` when absent will use `ts` as a replacement

  * ==== TYPE CONVERSIONS ====
  * Java types corresponding to the schema types. [[StreamDecoder]] should produce mutations that comply.
  * NOTE: everything is nullable (hence boxed)
  * IntType        java.lang.Integer
  * LongType       java.lang.Long
  * DoubleType     java.lang.Double
  * FloatType      java.lang.Float
  * ShortType      java.lang.Short
  * BooleanType    java.lang.Boolean
  * ByteType       java.lang.Byte
  * StringType     java.lang.String
  * BinaryType     Array[Byte]
  * ListType       java.util.List[Byte]
  * MapType        java.util.Map[Byte]
  * StructType     Array[Any]
  */
case class Mutation(schema: StructType = null, before: Array[Any] = null, after: Array[Any] = null)

abstract class StreamDecoder extends Serializable {
  def decode(bytes: Array[Byte]): Mutation
  def schema: StructType
}

// the implementer of this class should take a single argument, a scala map of string to string
// zipline framework will construct this object with user conf supplied via CLI
abstract class Api(userConf: Map[String, String]) extends Serializable {
  def streamDecoder(groupByServingInfoParsed: GroupByServingInfoParsed): StreamDecoder
  def genKvStore: KVStore
}
