package com.microsoft.ml.spark.explainers
import com.microsoft.ml.spark.core.contracts.HasOutputCol
import com.microsoft.ml.spark.core.schema.DatasetExtensions
import org.apache.spark.ml.Transformer
import org.apache.spark.ml.linalg.SQLDataTypes.VectorType
import org.apache.spark.ml.param.{DoubleParam, IntParam, ParamMap, ParamValidators, Params, _}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import org.apache.spark.ml.stat.Summarizer


trait ICEFeatureParams extends Params with HasNumSamples {
  val feature = new Param[String] (
    this,
    "feature",
    "Feature to explain"
  )
  def getFeature: String = $(feature)
  def setFeature(value: String): this.type = set(feature, value)

  val featureType = new Param[String] (
    this,
    "featureType",
    "Type of feature to explain",
    ParamValidators.inArray(Array("discrete", "continuous"))
  )
  def getFeatureType: String = $(featureType)
  def setFeatureType(value: String): this.type = set(featureType, value)

  val topNValues = new IntParam (
    this,
    "topNValues",
    "topNValues",
    ParamValidators.gt(0)
  )
  def getTopNValues: Int = $(topNValues)
  def setTopNValues(value: Int): this.type = set(topNValues, value)

  val nSplits = new IntParam (
    this,
    "nSplits",
    "nSplits",
    ParamValidators.gt(0)
  )
  def getNSplits: Int = $(nSplits)
  def setNSplits(value: Int): this.type = set(nSplits, value)

  val rangeMax = new DoubleParam(
    this,
    "rangeMax",
    "rangeMax",
    ParamValidators.gtEq(0.0)
  )
  def getRangeMax: Double = $(rangeMax)
  def setRangeMax(value: Double): this.type = set(rangeMax, value)

  val rangeMin = new DoubleParam(
    this,
    "rangeMin",
    "rangeMin",
    ParamValidators.gtEq(0.0)
  )
  def getRangeMin: Double = $(rangeMin)
  def setRangeMin(value: Double): this.type = set(rangeMin, value)

  val kind = new Param[String] (
    this,
    "kind",
    "pdp or ice",
    ParamValidators.inArray(Array("average", "individual"))
  )
  def getKind: String = $(kind)
  def setKind(value: String): this.type = set(kind, value)

  setDefault(numSamples -> 1000, featureType -> "discrete", topNValues -> 100, nSplits -> 20, kind -> "individual")

}

class ICETransformer(override val uid: String) extends Transformer
  with HasExplainTarget
  with HasModel
  with ICEFeatureParams
  with HasOutputCol {

  /* transform:
         1) gives feature values 
         2) individual series plots 

    */
  def this() = {
    this(Identifiable.randomUID("ICETransformer"))
  }

  def transform(ds: Dataset[_]): DataFrame = {

    val df = ds.toDF
    val idCol = DatasetExtensions.findUnusedColumnName("id", df)
    val targetClasses = DatasetExtensions.findUnusedColumnName("targetClasses", df)
    val dfWithId = df
      .withColumn(idCol, monotonically_increasing_id())
      .withColumn(targetClasses, this.get(targetClassesCol).map(col).getOrElse(lit(getTargetClasses)))

    transformSchema(df.schema)
    val feature = this.getFeature

    val values = getFeatureType.toLowerCase match {
      case "discrete" =>
        collectDiscreteValues(dfWithId)
      case "continuous" =>
        collectSplits(dfWithId, get(rangeMin), get(rangeMax))
    }

    val dataType = dfWithId.schema(feature).dataType
    val explodeFunc = explode(array(values.map(v => lit(v).cast(dataType)): _*))

    val predicted = getModel.transform(dfWithId.withColumn(feature, explodeFunc))
    val targetCol = DatasetExtensions.findUnusedColumnName("target", predicted)

    val explainTarget = extractTarget(predicted.schema, targetClasses)
    val result = predicted.withColumn(targetCol, explainTarget)

    //result.show()

    getKind.toLowerCase match {
      case "average" =>
        result
            .groupBy(feature)
            .agg(Summarizer.mean(col(targetCol)).alias("__feature__importance__"))
            .withColumnRenamed(feature, "__feature__value__")
            .withColumn("__feature__name__", lit(feature))
            .select("__feature__name__", "__feature__value__", "__feature__importance__")
      case "individual" =>
        // storing as a map feature -> target value
        result.groupBy("id")
          .agg(collect_list(feature).alias("feature_list"), collect_list(targetCol).alias("target_list"))
          .withColumn("__feature__importance__", map_from_arrays(col("feature_list"), col("target_list")))
          .select(idCol, "__feature__importance__")
          .orderBy(idCol)

    }
  }

  private def collectDiscreteValues[_](df: DataFrame): Array[_] = {
    val values = df
      .groupBy(col(getFeature))
      .agg(count("*").as("__feature__count__"))
      .orderBy(col("__feature__count__").desc)
      .head(getTopNValues)
      .map(row => row.get(0))
    values
  }

  private def collectSplits(df: DataFrame, rangeMin: Option[Double], rangeMax: Option[Double]): Array[Double] = {
    def createNSplits(n: Int)(from: Double, to: Double): Seq[Double] = {
      (0 to n) map {
        i => (to - from) / n * i + from
      }
    }

    val feature = getFeature
    val featureCol = df.schema(feature)
    val nSplits = getNSplits

    val createSplits = createNSplits(nSplits) _

    val values = if (rangeMin.isDefined && rangeMax.isDefined) {
      val (mi, ma) = (rangeMin.get, rangeMax.get)

      // The ranges are defined
      featureCol.dataType match {
        case _@(ByteType | IntegerType | LongType | ShortType) =>
          if (ma.toLong - mi.toLong <= nSplits) {
            // For integral types, no need to create more splits than needed.
            (mi.toLong to ma.toLong) map (_.toDouble)
          } else {
            createSplits(mi, ma)
          }
        case _ =>
          createSplits(mi, ma)
      }
    } else {
      // The ranges need to be calculated from background dataset.
      featureCol.dataType match {
        case _@(ByteType | IntegerType | LongType | ShortType) =>
          val Row(minValue: Long, maxValue: Long) = df
            .agg(min(col(feature)).cast(LongType), max(col(feature)).cast(LongType))
            .head

          val mi = rangeMin.map(_.toLong).getOrElse(minValue)
          val ma = rangeMax.map(_.toLong).getOrElse(maxValue)

          if (ma - mi <= nSplits) {
            // For integral types, no need to create more splits than needed.
            (mi to ma) map (_.toDouble)
          } else {
            createSplits(mi, ma)
          }
        case _ =>
          val Row(minValue: Double, maxValue: Double) = df
            .agg(min(col(feature)).cast(DoubleType), max(col(feature)).cast(DoubleType))
            .head

          val mi = rangeMin.getOrElse(minValue)
          val ma = rangeMax.getOrElse(maxValue)
          createSplits(mi, ma)
      }
    }

    values.toArray
  }

  override def copy(extra: ParamMap): Transformer = this.defaultCopy(extra)

  override def transformSchema(schema: StructType): StructType = {
    assert(!schema.fieldNames.contains(feature.name), s"The schema does not contain column ${feature.name}")
    this.validateSchema(schema)
    schema.add(getOutputCol, ArrayType(VectorType))
  }
}
