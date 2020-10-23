package ai.zipline.aggregator.windowing

import java.util

import ai.zipline.aggregator.base.DataType
import ai.zipline.aggregator.row.{Row, RowAggregator}
import ai.zipline.aggregator.windowing.HopsAggregator._
import ai.zipline.api.Config.Aggregation

// generate hops per spec, (NOT per window) for the given hop sizes in the resolution
// we use minQueryTs to construct only the relevant hops for a given hop size.
// 180day window , 5hr window (headStart(minTs) - 5hrs, maxTs)
// daily aggregates (headStart(minTs) - 180days, maxTs),
class HopsAggregator(minQueryTs: Long,
                     aggregations: Seq[Aggregation],
                     inputSchema: Seq[(String, DataType)],
                     resolution: Resolution)
    extends Serializable {

  @transient lazy val rowAggregator =
    new RowAggregator(inputSchema, aggregations.map(_.unWindowed))
  @transient lazy val hopSizes: Array[Long] = resolution.hopSizes
  @transient lazy val leftBoundaries: Array[Long] = {
    // Use the max window for a given tail hop to determine
    // from where(leftBoundary) a particular hops size is relevant
    val hopSizeToMaxWindow =
      aggregations
        .flatMap(_.windows)
        .groupBy(resolution.calculateTailHop)
        .mapValues(_.map(_.millis).max)

    val result = resolution.hopSizes.map { hop =>
      TsUtils.round(minQueryTs, hop) - hopSizeToMaxWindow.getOrElse(hop, 0L)
    }
    println(s"Left bounds = ${result.map(_.toString).mkString(",")} , minQueryTs = $minQueryTs")
    result
  }

  def init(): IrMapType =
    Array.fill(hopSizes.length)(new java.util.HashMap[Long, Array[Any]])

  private def buildHop(ts: Long): Array[Any] = {
    val v = new Array[Any](rowAggregator.length + 1)
    v.update(rowAggregator.length, ts)
    v
  }

  // used to collect hops of various sizes in a single pass of input rows
  def update(hopMaps: IrMapType, row: Row): IrMapType = {
    for (i <- hopSizes.indices) {
      if (row.ts >= leftBoundaries(i)) { // left inclusive
        val hopStart = TsUtils.round(row.ts, hopSizes(i))
        val hopIr = hopMaps(i).computeIfAbsent(hopStart, buildHop)
        rowAggregator.update(hopIr, row)
      }
    }
    hopMaps
  }

  // Zero-copy merging
  // NOTE: inputs will be mutated in the process, use "clone" if you want re-use references
  def merge(leftHops: IrMapType, rightHops: IrMapType): IrMapType = {
    if (leftHops == null) return rightHops
    if (rightHops == null) return leftHops
    for (i <- hopSizes.indices) { // left and right will be same size
      val leftMap = leftHops(i)
      val rightIter = rightHops(i).entrySet().iterator()
      while (rightIter.hasNext) {
        val entry = rightIter.next()
        val hopStart = entry.getKey
        val rightIr = entry.getValue
        val leftIr = leftMap.get(hopStart)
        if (leftIr != null) { // unfortunate that the option has to be created
          rowAggregator.merge(leftIr, rightIr)
        } else {
          leftMap.put(hopStart, rightIr)
        }
      }
    }
    leftHops
  }

  // order by hopStart
  @transient lazy val arrayOrdering: Ordering[Array[Any]] =
    (x: Array[Any], y: Array[Any]) =>
      Ordering[Long]
        .compare(x.last.asInstanceOf[Long], y.last.asInstanceOf[Long])

  def toTimeSortedArray(hopMaps: IrMapType): OutputArrayType =
    hopMaps.map { m =>
      val resultIt = m.values.iterator()
      val result = new Array[Array[Any]](m.size())
      for (i <- 0 until m.size()) {
        result.update(i, resultIt.next())
      }
      util.Arrays.sort(result, arrayOrdering)
      result
    }
}

object HopsAggregator {
  type OutputArrayType = Array[Array[Array[Any]]]
  type IrMapType = Array[java.util.HashMap[Long, Array[Any]]]
}
