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
        if (meanRatingUser(user, ratings) == 0.0) meanRatings(ratings)
        val r_u = meanRatingUser(user, ratings)
        val r_i = meanNormalizedItem(item, ratings)
        r_u + r_i * scale(r_u + r_i, r_u)
    }

    def mae(predict : (Int, Int, Seq[Rating]) => Double, train : Seq[Rating], test : Seq[Rating]) : Double = {
      mean(test.filter(_.rating != -1).map{case Rating(u,i,r) => (predict(u,i,train) - r).abs})
    }
}
