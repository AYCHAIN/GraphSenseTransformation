package at.ac.ait

import org.apache.spark.sql.SparkSession

import at.ac.ait.{Fields => F}
import at.ac.ait.storage._

object TransformationJob {

  case class AppArgs(
      source_keyspace: String,
      target_keyspace: String
  )

  object AppArgs {
    def empty = new AppArgs("", "")
  }

  def main(args: Array[String]) {

    val argsInstance: AppArgs = args.sliding(2, 1).toList.foldLeft(AppArgs.empty) {
      case (accumArgs, currArgs) => currArgs match {
        case Array("--source_keyspace", source_keyspace) =>
          accumArgs.copy(source_keyspace = source_keyspace)
        case Array("--target_keyspace", target_keyspace) =>
          accumArgs.copy(target_keyspace = target_keyspace)
        case _ => accumArgs
      }
    }

    if (argsInstance.source_keyspace == "" || argsInstance.target_keyspace == "") {
      Console.err.println("Usage: spark-submit [...] graphsense-transformation.jar" +
        " --source_keyspace SOURCE_KEYSPACE" +
        " --target_keyspace TARGET_KEYSPACE")
      sys.exit(1)
    }

    val src_keyspace = argsInstance.source_keyspace
    val keyspace = argsInstance.target_keyspace

    val spark = SparkSession.builder.appName(s"GraphSense Transformation [$keyspace]").getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    println("Source keyspace: " + src_keyspace)
    println("Target keyspace: " + keyspace)

    import spark.implicits._

    val cassandra = new CassandraStorage(spark)

    val blocks = cassandra.load[Block](src_keyspace, "block")
    val transactions = cassandra.load[Transaction](src_keyspace, "transaction")
    val exchangeRates = cassandra.load[ExchangeRates](src_keyspace, "exchange_rates")
    val tags = cassandra.load[Tag](src_keyspace, "tag")

    val transformation = new Transformation(spark, transactions, exchangeRates, tags)

    println("Extracting transaction inputs")
    val regInputs = transformation.computeRegularInputs(transactions).persist()
    println(s"Regular Inputs ${regInputs.count}")
    regInputs.show()

    println("Extracting transaction outputs")
    val regOutputs = transformation.computeRegularOutputs(transactions).persist()
    println(s"Regular Outputs ${regOutputs.count}")
    regOutputs.show()

    println("Computing address transactions")
    val addressTransactions =
      transformation.computeAddressTransactions(transactions, regInputs, regOutputs).persist()
    println(s"Address transactions ${addressTransactions.count}")
    addressTransactions.show()
    cassandra.store(keyspace, "address_transactions", addressTransactions)

    val(inputs, outputs) = transformation.splitTransactions(addressTransactions)
    inputs.persist()
    outputs.persist()

    println("Computing address statistics")
    val basicAddresses =
      transformation.computeBasicAddresses(addressTransactions, inputs, outputs, exchangeRates)
        .persist()
    println(s"Addresses ${addressTransactions.count}")

    println("Computing address relations")
    val addressRelations =
      transformation.computeAddressRelations(inputs,
                                             outputs,
                                             regInputs,
                                             transactions,
                                             basicAddresses,
                                             exchangeRates)
        .persist()
    addressRelations.show()
    cassandra.store(keyspace,
                    "address_incoming_relations",
                    addressRelations.sort(F.dstAddressPrefix))
    cassandra.store(keyspace,
                    "address_outgoing_relations",
                    addressRelations.sort(F.srcAddressPrefix))

    println("Computing addresses")
    val addresses = transformation.computeAddresses(basicAddresses, addressRelations)
    addresses.show()
    cassandra.store(keyspace, "address", addresses)

    println("Computing address tags")
    val addressTags = transformation.computeAddressTags(basicAddresses, tags).persist()
    addressTags.show()
    cassandra.store(keyspace, "address_tags", addressTags)

    println("Computing address clusters")
    val addressCluster = transformation.computeAddressCluster(regInputs, regOutputs).persist()
    addressCluster.show()
    cassandra.store(keyspace, "address_cluster", addressCluster)

    println("Computing cluster tags")
    // TODO: refactor
    //val clusterTags = transformation.computeClusterTags(clusterAdresses, tags).persist()
    val clusterTags = transformation.clusterTags
    clusterTags.show
    cassandra.store(keyspace, "cluster_tags", clusterTags)

    println("Computing cluster relations")
    // TODO: refactor
    val clusterRelations = transformation.clusterRelations
    clusterRelations.show
    cassandra.store(keyspace,
                    "cluster_incoming_relations",
                    clusterRelations.sort(F.dstCluster, F.srcCluster))
    cassandra.store(keyspace,
                    "cluster_outgoing_relations",
                    clusterRelations.sort(F.dstCluster, F.dstCluster))

    println("Computing clusters")
    // TODO: refactor
    val clusters = transformation.cluster
    clusters.show
    cassandra.store(keyspace, "cluster", clusters)

    println("Computing cluster addresses")
    // TODO: refactor
    val clusterAddresses = transformation.clusterAddresses
    clusterAddresses.show
    cassandra.store(keyspace, "cluster_addresses", clusterAddresses)

    println("Compute summary statistics")
    // TODO: refactor
    val summaryStatistics =
      transformation.computeSummaryStatistics(blocks,
                                              transactions,
                                              basicAddresses,
                                              addressRelations,
                                              transformation.basicCluster)
    summaryStatistics.show()
    cassandra.store(keyspace, "summary_statistics", summaryStatistics)

    spark.stop()
  }
}
