package com.hamstoo.models

import play.api.libs.json.JsObject

/**
  * A trait define functionality that's required by the frontend.
  * Mixed with Protectable trait, to return protected preview
  */
trait HasJsonPreview {

  /**
    * @return - Json object that contain data object preview information,
    *           based on template described below
    *             {
    *               "id": "String based identifier"
    *               "preview": "String or another Json object"
    *               "type": "For example `comment` or `highlight`"
    *             }
    */
  def jsonPreview: JsObject
}
