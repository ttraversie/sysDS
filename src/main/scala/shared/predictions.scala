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

  def meanRatingUser(user : Int, ratings : Seq[Rating]) : Double = {
        mean(ratings.filter(_.user == user).map{case Rating(u,i,r) => r})
    }

  def meanRatingItem(item : Int, ratings : Seq[Rating]) : Double = {
      mean(ratings.filter(_.item == item).map{case Rating(u,i,r) => r})
  }

  def meanRatings(ratings : Seq[Rating]) : Double = {
      mean(ratings.filter(_.rating != -1).map{case Rating(u,i,r) => r}) 
  }

  def scale(x : Double, y : Double) : Double = 
      if (x > y) 5-y else if (x < y) y-1 else 1

  def normalize(r_u_i : Double, user : Int, item : Int, ratings : Seq[Rating]) : Double = {
      val r_u = meanRatingUser(user, ratings)
      (r_u_i - r_u)/scale(r_u_i, r_u)
  }

  def meanNormalizedItem(item : Int, ratings : Seq[Rating]) : Double = {
      mean(ratings.filter(_.item == item).map{case Rating(u,i,r) => normalize(r, u, item, ratings)})
  }

  def prediction(user : Int, item : Int, ratings : Seq[Rating]) : Double = {
      val r_u = meanRatingUser(user, ratings)
      if (r_u == 0.0) meanRatings(ratings)
      val r_i = meanNormalizedItem(item, ratings)
      r_u + r_i * scale(r_u + r_i, r_u)
  }

  def mapUser(ratings : Seq[Rating]) : Map[Int,Double] = {
      val users = ratings.filter(_.user != -1).map{case Rating(u,i,r) => u}.distinct
      val map_u = (users.map{u => (u,meanRatingUser(u, ratings))}).toMap
      map_u
  }

  def mapItem(ratings : Seq[Rating]) : Map[Int,Double] = {
      val items = ratings.filter(_.item != -1).map{case Rating(u,i,r) => i}.distinct
      val map_i = (items.map{i => (i,meanRatingItem(i, ratings))}).toMap
      map_i
  }

  def mapItemNormalized(ratings : Seq[Rating]) : Map[Int,Double] = {
      val items = ratings.filter(_.item != -1).map{case Rating(u,i,r) => i}.distinct
      val map_i = (items.map{i => (i,meanNormalizedItem(i, ratings))}).toMap
      map_i
  }

  def predictionBaseline(ratings : Seq[Rating]) : ((Int,Int) => Double) = {
      val map_u = mapUser(ratings)
      val map_i_norm = mapItemNormalized(ratings)
      val mean = meanRatings(ratings)
      var r_u : Double = 0.0
      var r_i : Double = 0.0
      ((u,i) => {
      if (map_u contains u) {r_u = map_u(u)} else mean
      if (map_i_norm contains i) {r_i = map_i_norm(i)}
      r_u + r_i * scale(r_u + r_i, r_u)})
  }

  def predictionUser(ratings : Seq[Rating]) : ((Int,Int) => Double) = {
      val map_u = mapUser(ratings)
      val mean = meanRatings(ratings)
      ((u,i) => if (map_u contains u) {map_u(u)} else mean)
  }

  def predictionItem(ratings : Seq[Rating]) : ((Int,Int) => Double) = {
      val map_i = mapItem(ratings)
      val mean = meanRatings(ratings)
      ((u,i) => if (map_i contains i) {map_i(i)} else mean)
  }

  def predictionMean(ratings : Seq[Rating]) : ((Int,Int) => Double) = {
      val mean = meanRatings(ratings)
      ((u,i) => mean)
  }

  def mae(predictor : (Int,Int) => Double, test : Seq[Rating]) : Double = {
    mean(test.filter(_.rating != -1).map{case Rating(u,i,r) => (predictor(u,i) - r).abs})
  }

    // Personnalized ratings

    def normalizedRatings(map_u : Map[Int,Double], ratings : Seq[Rating]) : Seq[Rating] = {
      ratings.filter(_.user != -1).map{case Rating(u,i,r) => Rating(u,i,(r - map_u(u))/scale(r, map_u(u)))}
    } // map_u the map obtained by mapUser

    def norm2(s : Seq[Double]) : Double = {
      scala.math.sqrt(s.map(x => x*x).sum)
    }

    //def mapNorm2user(user : Int, map_u : Map[Int,Double], normalizedRatings : Seq[Rating]) : Map[Int,Double] = {
    //  val users = normalizedRatings.filter(_.user != -1).map{case Rating(u,i,r) => u}.distinct
    //  val map_uNorm = (users.map{u => norm2(normalizedRatings.filter(_.user == u).map{case Rating(u,i,r) => r})}).toMap
    //  map_uNorm
    //}

    def ratingsPreProcessed(normalizedRatings : Seq[Rating]) : Seq[Rating] = {
      val users = normalizedRatings.filter(_.user != -1).map{case Rating(u,i,r) => u}.distinct
      val map_uNorm = (users.map{u => (u,norm2(normalizedRatings.filter(_.user == u).map{case Rating(u,i,r) => r}))}).toMap
      normalizedRatings.filter(_.user != -1).map{case Rating(u,i,r) => Rating(u,i,r/map_uNorm(u))}
    }

    def sim(u : Int, v : Int, preProcessedRatings : Seq[Rating]) : Double = {
      var rating_u = preProcessedRatings.filter(_.user == u)
      var rating_v = preProcessedRatings.filter(_.user == v) 
      var map_u = (rating_u.map{case Rating(u,i,r) => (i,r)}).toMap
      var map_v = (rating_u.map{case Rating(v,i,r) => (i,r)}).toMap
      var items_uv = rating_v.map{case Rating(u,i,r) => i}.intersect(rating_u.map{case Rating(u,i,r) => i})
      var sim = items_uv.map{i => map_u(i)*map_v(i)}.sum
      sim
    }

    def ratingItemSim(u : Int, i : Int, normalizedRatings : Seq[Rating], preProcessedRatings : Seq[Rating], sim : (Int,Int,Seq[Rating]) => Double) : Double = {
      var rating_i = normalizedRatings.filter(_.item == i).map{case Rating(v,i,r) => (sim(u,v,preProcessedRatings),r)}
      var num = rating_i.map{case (sim,r) => sim*r}.sum
      var denom = rating_i.map{case (sim,r) => sim.abs}.sum
      if (denom != 0) num/denom else 0.0
    }

    def predictionPersonalized(u : Int, i : Int, normalizedRatings : Seq[Rating], preProcessedRatings : Seq[Rating], map_u : Map[Int,Double], meanRatings : Double, sim : (Int,Int,Seq[Rating]) => Double) : Double = {
        var r_u : Double = 0.0
        var r_i = ratingItemSim(u,i,normalizedRatings,preProcessedRatings,sim)
        if (map_u contains u) {r_u = map_u(u)} else meanRatings
        r_u + r_i * scale(r_u + r_i, r_u)
    }
}
