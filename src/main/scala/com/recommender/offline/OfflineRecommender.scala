package com.recommender.offline

import com.recommender.configs.{UserMongoDBConf, UserSparkConf}
import org.apache.spark.SparkConf
import org.apache.spark.ml.recommendation.ALS
import org.apache.spark.ml.recommendation.ALS.Rating
import org.apache.spark.sql.{DataFrame, Row, SparkSession}

object OfflineRecommender {
    def main(args: Array[String]): Unit = {

        // 创建spark session
        val sparkConf = new SparkConf().setMaster(UserSparkConf.cores).setAppName("OfflineRecommender")
        val spark = SparkSession.builder().config(sparkConf).getOrCreate()
        import spark.implicits._
        //读取mongoDB中的业务数
        val ratingRDD = spark
          .read
          .option("uri",UserMongoDBConf.uri)
          .option("collection",UserMongoDBConf.reviewCollection)
          .format("com.mongodb.spark.sql")
          .load()
          .select("reviewerID", "asin", "overall")
          .rdd
          .map(x => (x.getString(0), x.getString(1), x.getDouble(2)))
          .cache()

        //用户的数据集 RDD[Int]
        val userRDD = ratingRDD.map(_._1).distinct()
        val productRDD = ratingRDD.map(_._2).distinct()

        //创建训练数据集
        val trainData = ratingRDD.map(x => Rating(x._1,x._2,x._3.toFloat))
        // rank 是模型中隐语义因子的个数, iterations 是迭代的次数, lambda 是ALS的正则化参
        val (rank,iterations,lambda) = (50, 5, 0.01)
        // 调用ALS算法训练隐语义模型

        val model = ALS.train(trainData,rank=rank,maxIter = iterations,alpha = lambda)

        // 2. 获得预测评分矩阵，得到用户的推荐列表
        // 用userRDD和productRDD做一个笛卡尔积，得到空的userProductsRDD表示的评分矩阵
        val userProducts = userRDD.cartesian(productRDD)
        val preRating = model.predict(userProducts)
        // 从预测评分矩阵中提取得到用户推荐列表
        val userRecs = preRating.filter(_.rating>0)
          .map(
              rating => ( rating.user, ( rating.product, rating.rating ) )
          )
          .groupByKey()
          .map{
              case (userId, recs) =>
                  UserRecs( userId, recs.toList.sortWith(_._2>_._2).take(USER_MAX_RECOMMENDATION).map(x=>Recommendation(x._1,x._2)) )
          }
          .toDF()
        userRecs.write
          .option("uri", mongoConfig.uri)
          .option("collection", USER_RECS)
          .mode("overwrite")
          .format("com.mongodb.spark.sql")
          .save()

        // 3. 利用商品的特征向量，计算商品的相似度列表
        val productFeatures = model.productFeatures.map{
            case (productId, features) => ( productId, new DoubleMatrix(features) )
        }
        // 两两配对商品，计算余弦相似度
        val productRecs = productFeatures.cartesian(productFeatures)
          .filter{
              case (a, b) => a._1 != b._1
          }
          // 计算余弦相似度
          .map{
              case (a, b) =>
                  val simScore = consinSim( a._2, b._2 )
                  ( a._1, ( b._1, simScore ) )
          }
          .filter(_._2._2 > 0.4)
          .groupByKey()
          .map{
              case (productId, recs) =>
                  ProductRecs( productId, recs.toList.sortWith(_._2>_._2).map(x=>Recommendation(x._1,x._2)) )
          }
          .toDF()
        productRecs.write
          .option("uri", mongoConfig.uri)
          .option("collection", PRODUCT_RECS)
          .mode("overwrite")
          .format("com.mongodb.spark.sql")
          .save()

        spark.stop()
    }
    def consinSim(product1: DoubleMatrix, product2: DoubleMatrix): Double ={
        product1.dot(product2)/ ( product1.norm2() * product2.norm2() )
    }
}