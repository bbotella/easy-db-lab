package com.rustyrazorblade.easydblab.providers.ssm

import com.rustyrazorblade.easydblab.Context
import com.rustyrazorblade.easydblab.configuration.User
import com.rustyrazorblade.easydblab.providers.aws.AWSCredentialsManager
import org.koin.dsl.module
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider

/**
 * Koin module for the SSM Session Manager SSH transport.
 *
 * Provides SsmSessionCommandBuilder, bound to the profile's region and AWS identity. The route
 * that uses it is chosen by transport in the SSH module. The binding is inert until the `ssm`
 * route asks for it, and the credentials file is only written once a command is built.
 */
val ssmModule =
    module {
        single {
            val user = get<User>()
            val credentialsManager = AWSCredentialsManager(get<Context>().profileDir, get<AwsCredentialsProvider>())
            SsmSessionCommandBuilder(user.region, { SsmCliCredentials.forUser(user, credentialsManager) })
        }
    }
