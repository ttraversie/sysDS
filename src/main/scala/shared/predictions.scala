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

    def predictionBaseline(user : Int, item : Int, map_u : Map[Int,Double], map_i : Map[Int,Double], meanRatings : Double) : Double = {
        var r_u : Double = 0.0
        var r_i : Double = 0.0
        if (map_u contains user) {r_u = map_u(user)} else meanRatings
        if (map_i contains item) {r_i = map_i(item)}
        r_u + r_i * scale(r_u + r_i, r_u)
    }

    def predictionUser(user : Int, item : Int, map_u : Map[Int,Double], meanRatings : Double) : Double = {
        if (map_u contains user) {map_u(user)} else meanRatings
    }

    def predictionItem(user : Int, item : Int, map_i : Map[Int,Double], meanRatings : Double) : Double = {
        if (map_i contains item) {map_i(item)} else meanRatings
    }

    def mae(predict : (Int, Int, Seq[Rating]) => Double, train : Seq[Rating], test : Seq[Rating]) : Double = {
      mean(test.filter(_.rating != -1).map{case Rating(u,i,r) => (predict(u,i,train) - r).abs})
    }
}
