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

package org.apache.spark.deploy.security

import java.util.ServiceLoader

import scala.collection.JavaConverters._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.security.Credentials

import org.apache.spark.SparkConf
import org.apache.spark.internal.Logging
import org.apache.spark.util.Utils

/**
 * A ConfigurableCredentialManager to manage all the registered credential providers and offer
 * APIs for other modules to obtain credentials as well as renewal time. By default
 * [[HadoopFSCredentialProvider]], [[HiveCredentialProvider]] and [[HBaseCredentialProvider]] will
 * be loaded in if not explicitly disabled, any plugged-in credential provider wants to be
 * managed by ConfigurableCredentialManager needs to implement [[ServiceCredentialProvider]]
 * interface and put into resources/META-INF/services to be loaded by ServiceLoader.
 *
 * Also each credential provider is controlled by
 * spark.yarn.security.credentials.{service}.enabled, it will not be loaded in if set to false.
 * For example, Hive's credential provider [[HiveCredentialProvider]] can be enabled/disabled by
 * the configuration spark.yarn.security.credentials.hive.enabled.
 */
private[spark] class ConfigurableCredentialManager(
    sparkConf: SparkConf, hadoopConf: Configuration) extends Logging {
  private val deprecatedProviderEnabledConfig = "spark.yarn.security.tokens.%s.enabled"
  private val providerEnabledConfig = "spark.yarn.security.credentials.%s.enabled"

  // Maintain all the registered credential providers
  private val credentialProviders = getCredentialProviders()
  logDebug(s"Using the following credential providers: ${credentialProviders.keys.mkString(", ")}.")

  private def getCredentialProviders(): Map[String, ServiceCredentialProvider] = {
    val providers = loadCredentialProviders

    // Filter out credentials in which spark.yarn.security.credentials.{service}.enabled is false.
    providers.filter { p =>
      sparkConf.getOption(providerEnabledConfig.format(p.serviceName))
        .orElse {
          sparkConf.getOption(deprecatedProviderEnabledConfig.format(p.serviceName)).map { c =>
            logWarning(s"${deprecatedProviderEnabledConfig.format(p.serviceName)} is deprecated, " +
              s"using ${providerEnabledConfig.format(p.serviceName)} instead")
            c
          }
        }.map(_.toBoolean).getOrElse(true)
    }.map { p => (p.serviceName, p) }.toMap
  }

  protected def loadCredentialProviders: List[ServiceCredentialProvider] = {
    ServiceLoader.load(classOf[ServiceCredentialProvider], Utils.getContextOrSparkClassLoader)
      .asScala.toList
  }

    /**
   * Get credential provider for the specified service.
   */
  def getServiceCredentialProvider(service: String): Option[ServiceCredentialProvider] = {
    credentialProviders.get(service)
  }

  /**
   * Writes delegation tokens to creds.  Delegation tokens are fetched from all registered
   * providers.
   *
   * @return nearest time of next renewal, Long.MaxValue if all the credentials aren't renewable,
   *         otherwise the nearest renewal time of any credentials will be returned.
   */
  def obtainCredentials(hadoopConf: Configuration, creds: Credentials): Long = {
    credentialProviders.values.flatMap { provider =>
      if (provider.credentialsRequired(hadoopConf)) {
        provider.obtainCredentials(hadoopConf, sparkConf, creds)
      } else {
        logDebug(s"Service ${provider.serviceName} does not require a token." +
          s" Check your configuration to see if security is disabled or not.")
        None
      }
    }.foldLeft(Long.MaxValue)(math.min)
  }
}
