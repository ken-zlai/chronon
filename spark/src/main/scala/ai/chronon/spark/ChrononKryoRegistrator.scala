/*
 *    Copyright (C) 2023 The Chronon Authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package ai.chronon.spark

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, Serializer}
import com.yahoo.memory.Memory
import com.yahoo.sketches.ArrayOfStringsSerDe
import com.yahoo.sketches.cpc.CpcSketch
import com.yahoo.sketches.frequencies.ItemsSketch
import org.apache.spark.serializer.KryoRegistrator
import org.apache.spark.SPARK_VERSION

class CpcSketchKryoSerializer extends Serializer[CpcSketch] {
  override def write(kryo: Kryo, output: Output, sketch: CpcSketch): Unit = {
    val bytes = sketch.toByteArray
    output.writeInt(bytes.size)
    output.writeBytes(bytes)
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[CpcSketch]): CpcSketch = {
    val size = input.readInt()
    val bytes = input.readBytes(size)
    CpcSketch.heapify(bytes)
  }
}

class ItemsSketchKryoSerializer extends Serializer[ItemSketchSerializable] {
  override def write(kryo: Kryo, output: Output, sketch: ItemSketchSerializable): Unit = {
    val serDe = new ArrayOfStringsSerDe
    val bytes = sketch.sketch.toByteArray(serDe)
    output.writeInt(bytes.size)
    output.writeBytes(bytes)
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[ItemSketchSerializable]): ItemSketchSerializable = {
    val size = input.readInt()
    val bytes = input.readBytes(size)
    val serDe = new ArrayOfStringsSerDe
    val result = new ItemSketchSerializable
    result.sketch = ItemsSketch.getInstance[String](Memory.wrap(bytes), serDe)
    result
  }
}

class ChrononKryoRegistrator extends KryoRegistrator {
  // registering classes tells kryo to not send schema on the wire
  // helps shuffles and spilling to disk
  override def registerClasses(kryo: Kryo) {
    //kryo.setWarnUnregisteredClasses(true)
    val names = Seq(
      "org.apache.spark.sql.execution.joins.UnsafeHashedRelation",
      "org.apache.spark.internal.io.FileCommitProtocol$TaskCommitMessage",
      "org.apache.spark.sql.execution.datasources.ExecutedWriteSummary",
      "org.apache.spark.sql.execution.datasources.BasicWriteTaskStats",
      "org.apache.spark.sql.execution.datasources.WriteTaskResult",
      "org.apache.spark.sql.execution.datasources.InMemoryFileIndex",
      "org.apache.spark.sql.execution.joins.LongHashedRelation",
      "org.apache.spark.sql.execution.joins.LongToUnsafeRowMap",
      "org.apache.spark.sql.execution.streaming.sources.ForeachWriterCommitMessage$",
      "org.apache.spark.sql.types.Metadata",
      "ai.chronon.api.Row",
      "ai.chronon.spark.KeyWithHash",
      "ai.chronon.aggregator.windowing.BatchIr",
      "ai.chronon.online.RowWrapper",
      "ai.chronon.online.Fetcher$Request",
      "ai.chronon.aggregator.windowing.FinalBatchIr",
      "ai.chronon.online.LoggableResponse",
      "ai.chronon.online.LoggableResponseBase64",
      "com.yahoo.sketches.kll.KllFloatsSketch",
      "java.util.HashMap",
      "java.util.ArrayList",
      "java.util.HashSet",
      "org.apache.spark.sql.Row",
      "org.apache.spark.sql.catalyst.InternalRow",
      "org.apache.spark.sql.catalyst.expressions.GenericRow",
      "org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema",
      "org.apache.spark.sql.catalyst.expressions.UnsafeRow",
      "org.apache.spark.sql.types.StructField",
      "org.apache.spark.sql.types.StructType",
      "org.apache.spark.sql.types.LongType$", // dollar stands for case objects
      "org.apache.spark.sql.types.StringType",
      "org.apache.spark.sql.types.StringType$",
      "org.apache.spark.sql.types.IntegerType$",
      "org.apache.spark.sql.types.BinaryType",
      "org.apache.spark.sql.types.DataType",
      "org.apache.spark.sql.types.NullType$",
      "org.apache.spark.sql.types.DoubleType$",
      "org.apache.spark.sql.types.BooleanType$",
      "org.apache.spark.sql.types.BinaryType$",
      "org.apache.spark.sql.types.DateType$",
      "org.apache.spark.sql.types.TimestampType$",
      "org.apache.spark.util.sketch.BitArray",
      "org.apache.spark.util.sketch.BloomFilterImpl",
      "org.apache.spark.util.collection.BitSet",
      "org.apache.spark.util.collection.CompactBuffer",
      "scala.reflect.ClassTag$$anon$1",
      "scala.math.Ordering$$anon$4",
      "org.apache.spark.sql.catalyst.expressions.codegen.LazilyGeneratedOrdering",
      "org.apache.spark.sql.catalyst.expressions.SortOrder",
      "org.apache.spark.sql.catalyst.expressions.BoundReference",
      "org.apache.spark.sql.catalyst.trees.Origin",
      "org.apache.spark.sql.catalyst.expressions.Ascending$",
      "org.apache.spark.sql.catalyst.expressions.Descending$",
      "org.apache.spark.sql.catalyst.expressions.NullsFirst$",
      "org.apache.spark.sql.catalyst.expressions.NullsLast$",
      "scala.collection.IndexedSeqLike$Elements",
      "org.apache.spark.unsafe.types.UTF8String",
      "scala.reflect.ClassTag$GenericClassTag",
      "org.apache.spark.util.HadoopFSUtils$SerializableFileStatus",
      "org.apache.spark.sql.execution.datasources.v2.DataWritingSparkTaskResult",
      "org.apache.spark.sql.execution.joins.EmptyHashedRelation",
      "org.apache.spark.util.HadoopFSUtils$SerializableBlockLocation",
      "scala.reflect.ManifestFactory$LongManifest",
      "org.apache.spark.sql.execution.joins.EmptyHashedRelation$",
      "scala.reflect.ManifestFactory$$anon$1",
      "scala.reflect.ClassTag$GenericClassTag",
      "org.apache.spark.sql.execution.datasources.InMemoryFileIndex$SerializableFileStatus",
      "org.apache.spark.sql.execution.datasources.InMemoryFileIndex$SerializableBlockLocation",
      "scala.reflect.ManifestFactory$$anon$10",
      "org.apache.spark.sql.catalyst.InternalRow$$anonfun$getAccessor$8",
      "org.apache.spark.sql.catalyst.InternalRow$$anonfun$getAccessor$5",
      "scala.collection.immutable.ArraySeq$ofRef",
      "org.apache.spark.sql.catalyst.expressions.GenericInternalRow"
    )
    names.foreach { name =>
      try {
        kryo.register(Class.forName(name))
        kryo.register(Class.forName(s"[L$name;")) // represents array of a type to jvm
      } catch {
        case _: ClassNotFoundException => // do nothing
      }
    }
    kryo.register(classOf[Array[Array[Array[AnyRef]]]])
    kryo.register(classOf[Array[Array[AnyRef]]])
    kryo.register(classOf[CpcSketch], new CpcSketchKryoSerializer())
    kryo.register(classOf[ItemSketchSerializable], new ItemsSketchKryoSerializer())
    kryo.register(classOf[Array[ItemSketchSerializable]])
  }
}
