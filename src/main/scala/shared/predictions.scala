package shared

package object predictions
{
  case class Rating(user: Int, item: Int, rating: Double)

  def timingInMs(f : ()=>Double ) : (Double, Double) = {
    val start = System.nanoTime() 
    val output = f()
    val end = System.nanoTime()
    return (output, (end-start)/1000000.0)
  }

  def mean(s :Seq[Double]): Double =  if (s.size > 0) s.reduce(_+_) / s.length else 0.0
  def std(s :Seq[Double]): Double = {
    if (s.size == 0) 0.0
    else {
      val m = mean(s)
      scala.math.sqrt(s.map(x => scala.math.pow(m-x, 2)).sum / s.length.toDouble)
    }
  }

  def toInt(s: String): Option[Int] = {
    try {
      Some(s.toInt)
    } catch {
      case e: Exception => None
    }
  }

  def load(spark : org.apache.spark.sql.SparkSession,  path : String, sep : String) : org.apache.spark.rdd.RDD[Rating] = {
       val file = spark.sparkContext.textFile(path)
       return file
         .map(l => {
           val cols = l.split(sep).map(_.trim)
           toInt(cols(0)) match {
             case Some(_) => Some(Rating(cols(0).toInt, cols(1).toInt, cols(2).toDouble))
             case None => None
           }
       })
         .filter({ case Some(_) => true 
                   case None => false })
         .map({ case Some(x) => x 
                case None => Rating(-1, -1, -1)})
  }

  // Part 3: Baseline: Prediction based on Global Average Deviation

  // Compute the global average rating
  def meanRatings(ratings : Seq[Rating]) : Double = {
    mean(ratings.filter(_.rating != -1).map{case Rating(u,i,r) => r}) 
  }

  // Compute the average rating done by given user
  def meanRatingUser(user : Int, ratings : Seq[Rating]) : Double = {
    mean(ratings.filter(_.user == user).map{case Rating(u,i,r) => r})
  }

  // Compute the average rating for a given item
  def meanRatingItem(item : Int, ratings : Seq[Rating]) : Double = {
    mean(ratings.filter(_.item == item).map{case Rating(u,i,r) => r})
  }

  def scale(x : Double, y : Double) : Double = 
    if (x > y) 5-y else if (x < y) y-1 else 1

  // Create a Map: key = user, value = average of the ratings done by the user
  def mapUser(ratings : Seq[Rating]) : Map[Int,Double] = {
    val users = ratings.filter(_.user != -1).map{case Rating(u,i,r) => (u,r)}.groupBy(x => x._1)
    val map_u = users mapValues (x => mean(x.map{case (u,l) => l}))
    map_u
  }

  // Compute the normalized deviation
  def normalize(r_u_i : Double, user : Int, map_u : Map[Int,Double]) : Double = {
    val r_u = map_u(user)
    (r_u_i - r_u)/scale(r_u_i, r_u)
  }

  // Compute the global average deviation for an item
  def meanNormalizedItem(item : Int, ratings : Seq[Rating], map_u : Map[Int,Double]) : Double = {
    mean(ratings.filter(_.item == item).map{case Rating(u,i,r) => normalize(r, u, map_u)})
  }

  // Create a Map: key = item, value = average of the ratings for the item
  def mapItem(ratings : Seq[Rating]) : Map[Int,Double] = {
    val items = ratings.filter(_.item != -1).map{case Rating(u,i,r) => i}.distinct
    val map_i = (items.map{i => (i,meanRatingItem(i, ratings))}).toMap
    map_i
  }

  // Create a Map: key = item, value = global average deviation for the item
  def mapItemNormalized(ratings : Seq[Rating], map_u : Map[Int,Double]) : Map[Int,Double] = {
    val items = ratings.filter(_.item != -1).map{case Rating(u,i,r) => i}.distinct
    val map_i = (items.map{i => (i,meanNormalizedItem(i, ratings, map_u))}).toMap
    map_i
  }

  // Create the global average rating predictor
  def predictionMean(ratings : Seq[Rating]) : ((Int,Int) => Double) = {
    val mean = meanRatings(ratings)
    ((u,i) => mean)
  }

  // Create a predictor which returns the average rating of the item
  def predictionItem(ratings : Seq[Rating]) : ((Int,Int) => Double) = {
    val map_i = mapItem(ratings)
    val mean = meanRatings(ratings)
    ((u,i) => if (map_i contains i) {map_i(i)} else mean)
  }

  // Create a predictor which returns the average rating of the user
  def predictionUser(ratings : Seq[Rating]) : ((Int,Int) => Double) = {
    val map_u = mapUser(ratings)
    val mean = meanRatings(ratings)
    ((u,i) => if (map_u contains u) {map_u(u)} else mean)
  } 

  // Create the Baseline predictor
  def predictionBaseline(ratings : Seq[Rating]) : ((Int,Int) => Double) = {
    val map_u = mapUser(ratings)
    val map_i_norm = mapItemNormalized(ratings,map_u)
    val mean = meanRatings(ratings)
    var r_u : Double = 0.0
    var r_i : Double = 0.0
    ((u,i) => {
    if (map_u contains u) {r_u = map_u(u)} else mean
    if (map_i_norm contains i) {r_i = map_i_norm(i)}
    r_u + r_i * scale(r_u + r_i, r_u)})
  }

  // Compute the MAE for a given predictor
  def mae(predictor : (Int,Int) => Double, test : Seq[Rating]) : Double = {
    mean(test.filter(_.rating != -1).map{case Rating(u,i,r) => (predictor(u,i) - r).abs})
  }

  // Part 4: Spark Distribution Overhead

  def meanRDD(s : org.apache.spark.rdd.RDD[Double]): Double =  if (s.count() > 0) s.reduce(_+_) / s.count().toDouble else 0.0

  def meanRatingsRDD(rdd: org.apache.spark.rdd.RDD[Rating]): Double = { 
    meanRDD(rdd.filter(_.rating != -1).map{case Rating(u,i,r) => r})
  }

  def meanRatingUserRDD(user : Int, ratings : org.apache.spark.rdd.RDD[Rating]) : Double = {
    meanRDD(ratings.filter(_.user == user).map{case Rating(u,i,r) => r})
  }

  def meanRatingItemRDD(item : Int, ratings : org.apache.spark.rdd.RDD[Rating]) : Double = {
    meanRDD(ratings.filter(_.item == item).map{case Rating(u,i,r) => r})
  }

  def mapUserRDD(ratings : org.apache.spark.rdd.RDD[Rating]) : org.apache.spark.rdd.RDD[(Int,Double)] = {
    val users = ratings.filter(_.user != -1).map{case Rating(u,i,r) => (u,r)}.groupBy(x => x._1)
    val map_u = users mapValues (x => mean(x.toSeq.map{case (u,l) => l}))
    map_u
  }


  // Part 5: Personnalized predictions

  // Normalize the ratings
  def normalizedRatings(map_u : Map[Int,Double], ratings : Seq[Rating]) : Seq[Rating] = {
    ratings.filter(_.user != -1).map{case Rating(u,i,r) => Rating(u,i,(r - map_u(u))/scale(r, map_u(u)))}
  } // map_u the map obtained by mapUser

  def norm2(s : Seq[Double]) : Double = {
    scala.math.sqrt(s.map(x => x*x).sum)
  }

  // Preprocess the ratings
  def ratingsPreProcessed(normalizedRatings : Seq[Rating]) : Seq[Rating] = {
    val users = normalizedRatings.filter(_.user != -1).map{case Rating(u,i,r) => u}.distinct
    val map_uNorm = (users.map{u => (u,norm2(normalizedRatings.filter(_.user == u).map{case Rating(u,i,r) => r}))}).toMap
    normalizedRatings.filter(_.user != -1).map{case Rating(u,i,r) => Rating(u,i,r/map_uNorm(u))}
  }

  // Create a Map: key = user, 
  //               value = Map: key = item, value = prepreocessed rating of the item by the user

  def mapUserItems(preProcessedRatings : Seq[Rating]) : Map[Int,(Seq[Int],Map[Int,Double])] = {
    val users = preProcessedRatings.map{case Rating(u,i,r) => u}.distinct
    var mapUI = (users.map{u => (u,(preProcessedRatings.filter(_.user == u).map{case Rating(u,i,r) => i},(preProcessedRatings.filter(_.user == u).map{case Rating(u,i,r) => (i,r)}).toMap))}).toMap
    mapUI
  }

  // Compute the Adjusted Cosine similarity between two users
  def simCosine(u : Int, v : Int, mapUI : Map[Int,(Seq[Int],Map[Int,Double])]) : Double = {  
    if (!(mapUI contains u) || !(mapUI contains v)) 0.0
    var map_u = mapUI(u)
    var map_v = mapUI(v)
    var items_uv = (map_u._1).intersect(map_v._1)
    var sim = items_uv.map{i => map_u._2(i)*map_v._2(i)}.sum
    sim
  }

  // Compute the Jaccard similarity between two users
  def simJaccard(u : Int, v : Int, mapUI : Map[Int,(Seq[Int],Map[Int,Double])]) : Double = { 
    if (!(mapUI contains u) || !(mapUI contains v)) 0.0
    var map_u = mapUI(u)
    var map_v = mapUI(v)
    var items_uOrv = (map_u._1).union(map_v._1)
    var items_uAndv = (map_u._1).intersect(map_v._1)
    var sim = items_uAndv.length.toDouble / items_uOrv.length.toDouble
    sim
  }

  // Compute the user-specific weighted-sum deviation for an item and a user
  def ratingItemSim(u : Int, i : Int, normalizedRatings : Seq[Rating], sim : (Int,Int) => Double) : Double = {
    var rating_i = normalizedRatings.filter(_.item == i).map{case Rating(v,i,r) => (sim(u,v),r)}
    var num = rating_i.map{case (s,r) => s*r}.sum
    var denom = rating_i.map{case (s,r) => s.abs}.sum
    if (denom != 0) num/denom else 0.0
  }

  // Create the Personalized predictor
  def predictionPersonalized(ratings : Seq[Rating], similarity : String) : ((Int,Int) => Double) = {
    val map_u = mapUser(ratings)
    val normalized = normalizedRatings(map_u,ratings)
    val preProcessedRatings = ratingsPreProcessed(normalized)
    val mean = meanRatings(ratings)
    val mapUI = mapUserItems(preProcessedRatings)
    val sim : ((Int,Int) => Double) = {
            if (similarity == "Uniform") {(u,v) => 1.0}
            else if (similarity == "Jaccard") {(u,v) => simJaccard(u,v,mapUI)}
            else {(u,v) => simCosine(u,v,mapUI)}}
    var r_u : Double = 0.0
    ((u,i) => {
    var r_i = ratingItemSim(u,i,normalized,sim)
    if (map_u contains u) {r_u = map_u(u)} else mean
    r_u + r_i * scale(r_u + r_i, r_u)
    }) 
  }

// Part 6: Neighbourhood-Based Predictions

  // Create a Map: key = user, 
  //               value = Map: key = a kNN of the user, value: their Consine similarity
  def kNNusers(k : Int, normalized : Seq[Rating]) : Map[Int,Map[Int,Double]] = {
    val preProcessedRatings = ratingsPreProcessed(normalized)
    val mapUI = mapUserItems(preProcessedRatings)
    var mapkNN : Seq[(Int,Map[Int,Double])] = Seq()
    for ((u,valu) <- mapUI) {
      var simU : Seq[(Int,Double)] = Seq()
      for ((v,valv) <- mapUI) {
        if (u != v) {
          simU = simU :+ (v,simCosine(u,v,mapUI))
        }
      }
      simU = simU.sortBy(x => x._2)(Ordering[Double].reverse).take(k)
      var mapSimU = simU.toMap
      mapkNN = mapkNN :+ (u,mapSimU)
    }
    mapkNN.toMap
  }

  // Compute the kNN similarity between two users
  def simkNN(u : Int, v : Int, k : Int, normalized : Seq[Rating]) : Double = {
    val mapkNN = kNNusers(k,normalized)
    if (mapkNN(u) contains v) mapkNN(u)(v) else 0.0
  }

  // Compute the user-specific weighted-sum deviation for an item and a user for the kNN similarity
  def ratingItemkNN(u : Int, i : Int, normalizedRatings : Seq[Rating], mapkNN : Map[Int,Map[Int,Double]]) : Double = {
    var rating_i = normalizedRatings.filter(_.item == i).map{case Rating(v,i,r) => if (mapkNN(u) contains v) ((mapkNN(u)(v),r)) else ((0.0,r))}
    var num = rating_i.filter{case (s,r) => {s != 0}}.map{case (s,r) => s*r}.sum
    var denom = rating_i.filter{case (s,r) => {s != 0}}.map{case (s,r) => s.abs}.sum
    if (denom != 0) num/denom else 0.0
  }

  // Create the kNN predictor
  def predictionKNN(k : Int, ratings : Seq[Rating]) : ((Int,Int) => Double) = {
    val map_u = mapUser(ratings)
    val mean = meanRatings(ratings)
    val normalized = normalizedRatings(map_u,ratings)
    val mapkNN = kNNusers(k,normalized)
    var r_u : Double = 0.0
    ((u,i) => {
    var r_i = ratingItemkNN(u,i,normalized,mapkNN)
    if (map_u contains u) {r_u = map_u(u)} else mean
    r_u + r_i * scale(r_u + r_i, r_u)
    })
  }

  // Part 7: Recommendation

  // Recommend the top n items and the predicted score with respect to a given predictor
  def recommendation(u : Int, n : Int, ratings : Seq[Rating], predictor : (Int,Int) => Double) : Seq[(Int,Double)] = {
    val items = ratings.filter(_.item != -1).map{case Rating(v,i,r) => i}.distinct
    val items_u = ratings.filter(_.user == u).map{case Rating(u,i,r) => i}
    val not_rated = items.diff(items_u)
    var predictions = not_rated.map{i => (i,predictor(u,i))}
    (predictions.sortBy(x => x._1)(Ordering[Int])).sortBy(x => x._2)(Ordering[Double].reverse).take(n)
  }

}
