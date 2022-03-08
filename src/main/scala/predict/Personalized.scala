package predict

import org.rogach.scallop._
import org.apache.spark.rdd.RDD
import ujson._

import org.apache.spark.sql.SparkSession
import org.apache.log4j.Logger
import org.apache.log4j.Level

import scala.math
import shared.predictions._


class PersonalizedConf(arguments: Seq[String]) extends ScallopConf(arguments) {
  val train = opt[String](required = true)
  val test = opt[String](required = true)
  val separator = opt[String](default=Some("\t"))
  val num_measurements = opt[Int](default=Some(0))
  val json = opt[String]()
  verify()
}

object Personalized extends App {
  // Remove these lines if encountering/debugging Spark
  Logger.getLogger("org").setLevel(Level.OFF)
  Logger.getLogger("akka").setLevel(Level.OFF)
  val spark = SparkSession.builder()
    .master("local[1]")
    .getOrCreate()
  spark.sparkContext.setLogLevel("ERROR") 

  println("")
  println("******************************************************")

  var conf = new PersonalizedConf(args) 
  println("Loading training data from: " + conf.train()) 
  val train = load(spark, conf.train(), conf.separator()).collect()
  println("Loading test data from: " + conf.test()) 
  val test = load(spark, conf.test(), conf.separator()).collect()
  
  // Compute here

  val predictorUniform = predictionPersonalized(train,"Uniform")
  val predictorCosine = predictionPersonalized(train,"Cosine")
  val predictorJaccard = predictionPersonalized(train,"Jaccard")
  val map_u = mapUser(train)
  val normRatings = normalizedRatings(map_u,train)
  val ratingsPP = ratingsPreProcessed(normRatings)
  val mapUI = mapUserItems(ratingsPP)

  // Save answers as JSON
  def printToFile(content: String, 
                  location: String = "./answers.json") =
    Some(new java.io.PrintWriter(location)).foreach{
      f => try{
        f.write(content)
      } finally{ f.close }
  }
  conf.json.toOption match {
    case None => ; 
    case Some(jsonFile) => {
      val answers = ujson.Obj(
        "Meta" -> ujson.Obj(
          "1.Train" -> ujson.Str(conf.train()),
          "2.Test" -> ujson.Str(conf.test()),
          "3.Measurements" -> ujson.Num(conf.num_measurements())
        ),
        "P.1" -> ujson.Obj(
          "1.PredUser1Item1" -> ujson.Num(predictorUniform(1,1)), // Prediction of item 1 for user 1 (similarity 1 between users)
          "2.OnesMAE" -> ujson.Num(mae((a,b) => predictorUniform(a,b),test))         // MAE when using similarities of 1 between all users
        ),
        "P.2" -> ujson.Obj(
          "1.AdjustedCosineUser1User2" -> ujson.Num(simCosine(1,2,mapUI)), // Similarity between user 1 and user 2 (adjusted Cosine)
          "2.PredUser1Item1" -> ujson.Num(predictorCosine(1,1)),  // Prediction item 1 for user 1 (adjusted cosine)
          "3.AdjustedCosineMAE" -> ujson.Num(mae((a,b) => predictorCosine(a,b),test)) // MAE when using adjusted cosine similarity
        ),
        "P.3" -> ujson.Obj(
          "1.JaccardUser1User2" -> ujson.Num(simJaccard(1,2,mapUI)), // Similarity between user 1 and user 2 (jaccard similarity)
          "2.PredUser1Item1" -> ujson.Num(predictorJaccard(1,1)),  // Prediction item 1 for user 1 (jaccard)
          "3.JaccardPersonalizedMAE" -> ujson.Num(mae((a,b) => predictorJaccard(a,b),test)) // MAE when using jaccard similarity
        )
      )
      val json = write(answers, 4)
      println(json)
      println("Saving answers in: " + jsonFile)
      printToFile(json, jsonFile)
    }
  }

  println("")
  spark.close()
}
