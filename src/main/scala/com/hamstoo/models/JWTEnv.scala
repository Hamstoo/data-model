package com.hamstoo.models

import com.mohiva.play.silhouette.api.Env
import com.mohiva.play.silhouette.impl.authenticators.JWTAuthenticator

/**
  * The authentication type combination for endpoints Silhouette setup. Carries two types for Identity implementation
  * and Authenticator implementation, and is used as a type parameter for Silhouette Environment instance in
  * controllers.
  */
trait JWTEnv extends Env {
  type I = User
  type A = JWTAuthenticator
}
