/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.yarn.security

import java.util.ServiceLoader

import com.google.common.annotations.VisibleForTesting

import scala.collection.JavaConverters._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import org.apache.hadoop.security.Credentials
import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.deploy.security.ConfigurableCredentialManager
import org.apache.spark.internal.Logging
import org.apache.spark.util.Utils
import org.apache.spark.internal.config._
import org.apache.spark.deploy.yarn.config._
import org.apache.hadoop.fs.Path
import org.apache.hadoop.mapred.Master

/**
 * This class exists for backwards compatibility.  It loads services registered under the
 * deprecated [[ServiceCredentialProvider]].
 */
private[yarn] class YARNConfigurableCredentialManager(
    sparkConf: SparkConf,
    hadoopConf: Configuration,
    fileSystems: Set[FileSystem]) extends Logging {

  private val configurableCredentialManager =
    new ConfigurableCredentialManager(sparkConf, hadoopConf, fileSystems)

  // public for testing
  val deprecatedCredentialProviders = getDeprecatedCredentialProviders

  def obtainYARNCredentials(
    hadoopConf: Configuration,
    creds: Credentials): Long = {

    val superInterval = configurableCredentialManager.obtainCredentials(
      hadoopConf,
      creds)

    deprecatedCredentialProviders.values.flatMap { provider =>
      if (provider.credentialsRequired(hadoopConf)) {
        provider.obtainCredentials(hadoopConf, sparkConf, creds)
      } else {
        logDebug(s"Service ${provider.serviceName} does not require a token." +
          s" Check your configuration to see if security is disabled or not.")
        None
      }
    }.foldLeft(superInterval)(math.min)
  }


  private def getDeprecatedCredentialProviders:
    Map[String, ServiceCredentialProvider] = {
    val deprecatedProviders = loadDeprecatedCredentialProviders

    deprecatedProviders.
      filter(p => configurableCredentialManager.isServiceEnabled(p.serviceName))
      .map(p => (p.serviceName, p))
      .toMap
  }

  private def loadDeprecatedCredentialProviders:
    List[ServiceCredentialProvider] = {
    ServiceLoader.load(
      classOf[ServiceCredentialProvider],
      Utils.getContextOrSparkClassLoader)
      .asScala
      .toList
  }
}
