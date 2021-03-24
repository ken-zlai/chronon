package ai.zipline.spark

import ai.zipline.api.Constants
import org.apache.spark.sql.functions.{rand, round}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

case class TableUtils(sparkSession: SparkSession) {

  sparkSession.sparkContext.setLogLevel("ERROR")
  // converts String-s like "a=b/c=d" to Map("a" -> "b", "c" -> "d")
  def parsePartition(pstring: String): Map[String, String] = {
    pstring
      .split("/")
      .map { part =>
        val p = part.split("=", 2)
        p(0) -> p(1)
      }
      .toMap
  }

  def sql(query: String): DataFrame = {
    println(s"\n----[Running query]----\n$query\n----[End of Query]----\n")
    val df = sparkSession.sql(query)
    df
  }

  def partitions(tableName: String): Seq[String] = {
    if (!sparkSession.catalog.tableExists(tableName)) return Seq.empty[String]
    sparkSession.sqlContext
      .sql(s"SHOW PARTITIONS $tableName")
      .collect()
      .flatMap { row => parsePartition(row.getString(0)).get(Constants.PartitionColumn) }
  }

  def firstUnavailablePartition(tableName: String): Option[String] =
    partitions(tableName)
      .reduceOption(Ordering[String].max)
      .map(Constants.Partition.after)

  def firstAvailablePartition(tableName: String): Option[String] =
    partitions(tableName)
      .reduceOption(Ordering[String].min)

  def insertPartitions(df: DataFrame,
                       tableName: String,
                       tableProperties: Map[String, String] = null,
                       partitionColumns: Seq[String] = Seq(Constants.PartitionColumn),
                       saveMode: SaveMode = SaveMode.Overwrite,
                       fileFormat: String = "PARQUET"): Unit = {
    // partitions to the last
    val dfRearranged: DataFrame = if (!df.columns.endsWith(partitionColumns)) {
      val colOrder = df.columns.diff(partitionColumns) ++ partitionColumns
      df.select(colOrder.map(df.col): _*)
    } else {
      df
    }

    if (!sparkSession.catalog.tableExists(tableName)) {
      sql(createTableSql(tableName, dfRearranged.schema, partitionColumns, tableProperties, fileFormat))
    } else {
      if (tableProperties != null && tableProperties.nonEmpty) {
        sql(alterTablePropertiesSql(tableName, tableProperties))
      }
    }

    val rowCount = df.count()
    println(s"$rowCount rows requested to be written into table $tableName")

    if (rowCount > 0) {
      val rddPartitionCount = math.min(5000, math.ceil(rowCount / 1000000.0).toInt)
      println(s"repartitioning data for table $tableName into $rddPartitionCount rdd partitions")

      val saltCol = "random_partition_salt"
      val saltedDf = dfRearranged.withColumn(saltCol, round(rand() * 1000000))
      saltedDf
        .repartition(rddPartitionCount, Seq(Constants.PartitionColumn, saltCol).map(saltedDf.col): _*)
        .drop(saltCol)
        .write
        .mode(saveMode)
        .insertInto(tableName)
      println(s"Finished writing to $tableName")
    }
  }

  private def createTableSql(tableName: String,
                             schema: StructType,
                             partitionColumns: Seq[String],
                             tableProperties: Map[String, String],
                             fileFormat: String): String = {
    val fieldDefinitions = schema
      .filterNot(field => partitionColumns.contains(field.name))
      .map(field => s"${field.name} ${field.dataType.catalogString}")
    val createFragment =
      s"""CREATE TABLE $tableName (
         |    ${fieldDefinitions.mkString(",\n    ")}
         |)""".stripMargin
    val partitionFragment = if (partitionColumns != null && partitionColumns.nonEmpty) {
      val partitionDefinitions = schema
        .filter(field => partitionColumns.contains(field.name))
        .map(field => s"${field.name} ${field.dataType.catalogString}")
      s"""PARTITIONED BY (
         |    ${partitionDefinitions.mkString(",\n    ")}
         |)""".stripMargin
    } else {
      ""
    }
    val propertiesFragment = if (tableProperties != null && tableProperties.nonEmpty) {
      s"""TBLPROPERTIES (
         |    ${tableProperties.transform((k, v) => s"'$k'='$v'").values.mkString(",\n   ")}
         |)""".stripMargin
    } else {
      ""
    }
    Seq(createFragment, partitionFragment, s"STORED AS $fileFormat", propertiesFragment).mkString("\n")
  }

  private def alterTablePropertiesSql(tableName: String, properties: Map[String, String]): String = {
    // Only SQL api exists for setting TBLPROPERTIES
    val propertiesString = properties
      .map {
        case (key, value) =>
          s"'$key' = '$value'"
      }
      .mkString(", ")
    s"ALTER TABLE $tableName SET TBLPROPERTIES ($propertiesString)"
  }

  // logic for resuming computation from a previous job
  // applicable to join, joinPart, groupBy, daily_cache
  // TODO: Log each step - to make it easy to follow the range inference logic
  def unfilledRange(outputTable: String,
                    partitionRange: PartitionRange,
                    inputTables: Seq[String] = Seq.empty[String]): PartitionRange = {
    val inputStart = inputTables
      .flatMap(firstAvailablePartition)
      .reduceLeftOption(Ordering[String].min)
    val resumePartition = firstUnavailablePartition(outputTable)
    val effectiveStart = (inputStart ++ resumePartition ++ Option(partitionRange.start))
      .reduceLeftOption(Ordering[String].max)
    val result = PartitionRange(effectiveStart.orNull, partitionRange.end)
    result
  }
}