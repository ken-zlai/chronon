package ai.chronon.spark.test

import ai.chronon.api.Extensions.{LabelPartOps, MetadataOps}
import ai.chronon.api.{Builders, LongType, StringType, StructField, StructType}
import ai.chronon.spark.{Comparison, LabelJoin, SparkSessionBuilder, TableUtils}
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.functions.{max, min}
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureWithLabelJoinTest {
  val spark: SparkSession = SparkSessionBuilder.build("FeatureWithLabelJoinTest", local = true)

  private val namespace = "final_join"
  private val tableName = "test_feature_label_join"
  spark.sql(s"CREATE DATABASE IF NOT EXISTS $namespace")
  private val tableUtils = TableUtils(spark)

  private val labelDS = "2022-10-30"
  private val viewsGroupBy = TestUtils.createViewsGroupBy(namespace, spark)
  private val left = viewsGroupBy.groupByConf.sources.get(0)

  @Test
  def testFinalViews(): Unit = {
    // create test feature join table
    val featureTable = s"${namespace}.${tableName}"
    createTestFeatureTable().write.saveAsTable(featureTable)

    val labelJoinConf = createTestLabelJoin(50, 20)
    val joinConf = Builders.Join(
      Builders.MetaData(name = tableName, namespace = namespace, team = "chronon"),
      left,
      joinParts = Seq.empty,
      labelPart = labelJoinConf
    )

    val runner = new LabelJoin(joinConf, tableUtils, labelDS)
    val labelDf = runner.computeLabelJoin()
    println(" == First Run Label version 2022-10-30 == ")
    prefixColumnName(labelDf, exceptions = labelJoinConf.rowIdentifier(null, tableUtils.partitionColumn))
                                                        .show()
    val featureDf = tableUtils.sparkSession.table(joinConf.metaData.outputTable)
    println(" == Features == ")
    featureDf.show()
    val computed = tableUtils.sql(s"select * from ${joinConf.metaData.outputFinalView}")
    val expectedFinal = featureDf.join(prefixColumnName(labelDf,
                                                        exceptions = labelJoinConf.rowIdentifier(null,
                                                                                                  tableUtils.partitionColumn)),
                                       labelJoinConf.rowIdentifier(null, tableUtils.partitionColumn),
                                       "left_outer")
    assertResult(computed, expectedFinal)

    // add another label version
    val secondRun = new LabelJoin(joinConf, tableUtils, "2022-11-11")
    val secondLabel = secondRun.computeLabelJoin()
    println(" == Second Run Label version 2022-11-11 == ")
    secondLabel.show()
    val view = tableUtils.sql(s"select * from ${joinConf.metaData.outputFinalView} order by label_ds")
    view.show()
    // listing 4 should not have any 2022-11-11 version labels
    assertEquals(null,
                 view
                   .where(view("label_ds") === "2022-11-11" && view("listing") === "4")
                   .select("label_listing_labels_dim_room_type")
                   .first()
                   .get(0))
    // 11-11 label record number should be same as 10-30 label version record number
    assertEquals(view.where(view("label_ds") === "2022-10-30").count(),
                 view.where(view("label_ds") === "2022-11-11").count())
    // listing 5 should not not have any label
    assertEquals(null,
                 view
                   .where(view("listing") === "5")
                   .select("label_ds")
                   .first()
                   .get(0))

    //validate the latest label view
    val latest = tableUtils.sql(s"select * from ${joinConf.metaData.outputLatestLabelView} order by label_ds")
    latest.show()
    // latest label should be all same "2022-11-11"
    assertEquals(latest.agg(max("label_ds")).first().getString(0), latest.agg(min("label_ds")).first().getString(0))
    assertEquals("2022-11-11", latest.agg(max("label_ds")).first().getString(0))
  }

  @Test
  def testFinalViewsWithAggLabel(): Unit = {
    // create test feature join table
    val tableName = "label_agg_table"
    val featureTable = s"${namespace}.${tableName}"
    val featureRows = List(
      Row(1L, 24L, "US", "2022-10-02", "2022-10-02 16:00:00"),
      Row(1L, 20L, "US", "2022-10-03", "2022-10-03 10:00:00"),
      Row(2L, 38L, "US", "2022-10-02", "2022-10-02 11:00:00"),
      Row(3L, 41L, "US", "2022-10-02", "2022-10-02 22:00:00"),
      Row(3L, 19L, "CA", "2022-10-03", "2022-10-03 08:00:00"),
      Row(4L, 2L, "MX", "2022-10-02", "2022-10-02 18:00:00")
    )
    createTestFeatureTable(tableName, featureRows).write.saveAsTable(featureTable)

    val rows = List(
      Row(1L, 20L, "2022-10-02 11:00:00", "2022-10-02"),
      Row(2L, 30L, "2022-10-02 11:00:00", "2022-10-02"),
      Row(3L, 10L, "2022-10-02 11:00:00", "2022-10-02"),
      Row(1L, 20L, "2022-10-03 11:00:00", "2022-10-03"),
      Row(2L, 35L, "2022-10-03 11:00:00", "2022-10-03"),
      Row(3L, 15L, "2022-10-03 11:00:00", "2022-10-03")
    )
    val leftSource = TestUtils
      .createViewsGroupBy(namespace, spark, tableName = "listing_view_agg", customRows = rows)
      .groupByConf
      .sources
      .get(0)
    val labelJoinConf = createTestAggLabelJoin(5, "listing_labels_agg")
    val joinConf = Builders.Join(
      Builders.MetaData(name = tableName, namespace = namespace, team = "chronon"),
      leftSource,
      joinParts = Seq.empty,
      labelPart = labelJoinConf
    )

    val runner = new LabelJoin(joinConf, tableUtils, "2022-10-06")
    val labelDf = runner.computeLabelJoin()
    println(" == Label DF == ")
    prefixColumnName(labelDf, exceptions = labelJoinConf.rowIdentifier(null, tableUtils.partitionColumn))
                                                        .show()
    val featureDf = tableUtils.sparkSession.table(joinConf.metaData.outputTable)
    println(" == Features DF == ")
    featureDf.show()
    val computed = tableUtils.sql(s"select * from ${joinConf.metaData.outputFinalView}")
    val expectedFinal = featureDf.join(prefixColumnName(labelDf,
                                                        exceptions = labelJoinConf.rowIdentifier(null, tableUtils.partitionColumn)),
                                       labelJoinConf.rowIdentifier(null, tableUtils.partitionColumn),
                                       "left_outer")
    assertResult(computed, expectedFinal)

    // add new labels
    val newLabelRows = List(
      Row(1L, 0, "2022-10-07", "2022-10-07 11:00:00"),
      Row(2L, 2, "2022-10-07", "2022-10-07 11:00:00"),
      Row(3L, 2, "2022-10-07", "2022-10-07 11:00:00")
    )
    TestUtils.createOrUpdateLabelGroupByWithAgg(namespace, spark, 5, "listing_labels_agg", newLabelRows)
    val runner2 = new LabelJoin(joinConf, tableUtils, "2022-10-07")
    val updatedLabelDf = runner2.computeLabelJoin()
    updatedLabelDf.show()

    //validate the label view
    val latest = tableUtils.sql(s"select * from ${joinConf.metaData.outputLatestLabelView} order by label_ds")
    latest.show()
    assertEquals(2,
                 latest
                   .where(latest("listing") === "3" && latest("ds") === "2022-10-03")
                   .select("label_listing_labels_agg_is_active_max_5d")
                   .first()
                   .get(0))
    assertEquals("2022-10-07",
                 latest
                   .where(latest("listing") === "1" && latest("ds") === "2022-10-03")
                   .select("label_ds")
                   .first()
                   .get(0))
  }

  private def assertResult(computed: DataFrame, expected: DataFrame): Unit = {
    println(" == Computed == ")
    computed.show()
    println(" == Expected == ")
    expected.show()
    val diff = Comparison.sideBySide(computed, expected, List("listing", "ds", "label_ds"))
    if (diff.count() > 0) {
      println(s"Actual count: ${computed.count()}")
      println(s"Expected count: ${expected.count()}")
      println(s"Diff count: ${diff.count()}")
      println(s"diff result rows")
      diff.show()
    }
    assertEquals(0, diff.count())
  }

  private def prefixColumnName(df: DataFrame,
                               prefix: String = "label_",
                               exceptions: Array[String] = null): DataFrame = {
    println("exceptions")
    println(exceptions.mkString(", "))
    val renamedColumns = df.columns
      .map(col => {
        if (exceptions.contains(col) || col.startsWith(prefix)) {
          df(col)
        } else {
          df(col).as(s"$prefix$col")
        }
      })
    df.select(renamedColumns: _*)
  }

  def createTestLabelJoin(startOffset: Int,
                          endOffset: Int,
                          groupByTableName: String = "listing_labels"): ai.chronon.api.LabelPart = {
    val labelGroupBy = TestUtils.createRoomTypeGroupBy(namespace, spark, groupByTableName)
    Builders.LabelPart(
      labels = Seq(
        Builders.JoinPart(groupBy = labelGroupBy.groupByConf)
      ),
      leftStartOffset = startOffset,
      leftEndOffset = endOffset
    )
  }

  def createTestAggLabelJoin(windowSize: Int,
                             groupByTableName: String = "listing_labels_agg"): ai.chronon.api.LabelPart = {
    val labelGroupBy = TestUtils.createOrUpdateLabelGroupByWithAgg(namespace, spark, windowSize, groupByTableName)
    Builders.LabelPart(
      labels = Seq(
        Builders.JoinPart(groupBy = labelGroupBy.groupByConf)
      ),
      leftStartOffset = windowSize,
      leftEndOffset = windowSize
    )
  }

  def createTestFeatureTable(tableName: String = tableName, customRows: List[Row] = List.empty): DataFrame = {
    val schema = StructType(
      tableName,
      Array(
        StructField("listing", LongType),
        StructField("feature_review", LongType),
        StructField("feature_locale", StringType),
        StructField("ds", StringType),
        StructField("ts", StringType)
      )
    )
    val rows = if (customRows.isEmpty) {
      List(
        Row(1L, 20L, "US", "2022-10-01", "2022-10-01 10:00:00"),
        Row(2L, 38L, "US", "2022-10-02", "2022-10-02 11:00:00"),
        Row(3L, 19L, "CA", "2022-10-01", "2022-10-01 08:00:00"),
        Row(4L, 2L, "MX", "2022-10-02", "2022-10-02 18:00:00"),
        Row(5L, 139L, "EU", "2022-10-01", "2022-10-01 22:00:00"),
        Row(1L, 24L, "US", "2022-10-02", "2022-10-02 16:00:00")
      )
    } else customRows
    TestUtils.makeDf(spark, schema, rows)
  }
}