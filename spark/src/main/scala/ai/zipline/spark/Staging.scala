package ai.zipline.spark

import ai.zipline.spark.Extensions._
import org.apache.spark.sql.DataFrame

// resume functionality common to stagingQuery, joinPart/groupBy, join
case class Staging(outputTable: String, tableUtils: TableUtils, expectedRange: PartitionRange) {
//  def fill(fillFunction: PartitionRange => DataFrame, inputTables: Seq[String]): DataFrame = {
//    val fillableRange = tableUtils.unfilledRange(outputTable, expectedRange, inputTables)
//    val increment = fillFunction(fillableRange)
//    increment.savePartitionCounted(outputTable, fillableRange)
//    if (fillableRange == expectedRange) {
//      increment
//    } else {
//      tableUtils.sql(expectedRange.genScanQuery(null, outputTable))
//    }
//  }
//
//  def query(query: String, inputTables: Seq[String]): DataFrame = {
//    fill({ fillableRange =>
//           tableUtils.sql(fillableRange.substituteMacros(query))
//         },
//         inputTables)
//  }
}
