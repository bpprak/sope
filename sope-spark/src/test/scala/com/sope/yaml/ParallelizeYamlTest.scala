package com.sope.yaml

import com.sope.TestContext
import com.sope.spark.yaml.ParallelizeYaml
import org.scalatest.{FlatSpec, Matchers}

/**
  * @author mbadgujar
  */
class ParallelizeYamlTest extends FlatSpec with Matchers {

  "ParallelizeYaml" should "should parallelize the local yaml to Dataframe correctly" in {
    val context = TestContext.getSQlContext
    val df = ParallelizeYaml("data/local_data.yaml").parallelize(context)
    df.count() should be(2)
    df.show()
  }
}
