package ai.zipline.spark.streaming

import ai.zipline.aggregator.base.BottomK
import ai.zipline.api.UnknownType
import ai.zipline.spark.consistency.EditDistance
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, ListTopicsOptions}

import java.util
import java.util.Properties
import scala.collection.JavaConverters.{asScalaBufferConverter, asScalaIteratorConverter}

object TopicChecker {
  def topicShouldExist(topic: String, bootstrap: String): Unit = {
    val props = new Properties()
    props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
    try {
      val adminClient = AdminClient.create(props)
      val options = new ListTopicsOptions()
      options.listInternal(true)
      val topicsList = adminClient.listTopics(options)
      val topicsResult = topicsList.namesToListings().get()
      if (!topicsResult.containsKey(topic)) {
        val closestK = new BottomK[(Double, String)](UnknownType(), 5)
        val result = new util.ArrayList[(Double, String)]()
        topicsResult // find closestK matches based on edit distance.
          .entrySet()
          .iterator()
          .asScala
          .map { topicListing =>
            val existing = topicListing.getValue.name()
            EditDistance.betweenStrings(existing, topic).total / existing.length.toDouble -> existing
          }
          .foldLeft(result)((cnt, elem) => closestK.update(cnt, elem))
        closestK.finalize(result)
        throw new RuntimeException(s"""
                                      |Requested topic: $topic is not found in broker: $bootstrap.
                                      |Either the bootstrap is incorrect or the topic is. 
                                      |
                                      | ------ Most similar topics are ------
                                      |
                                      |  ${result.asScala.map(_._2).mkString("\n  ")}
                                      |
                                      | ------ End ------
                                      |""".stripMargin)
      } else {
        println(s"Found topic $topic in bootstrap $bootstrap.")
      }
    } catch {
      case ex: Exception => throw new RuntimeException(s"Failed to check for topic ${topic} in ${bootstrap}", ex)
    }
  }

  def main(args: Array[String]): Unit = {
    println(args.toSeq)
    val topic = args(0)
    val bootstrap = args(1)
    topicShouldExist(topic, bootstrap)
  }
}
