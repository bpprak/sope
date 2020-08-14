package com.sope.common.transform.model.action

import com.fasterxml.jackson.annotation.JsonProperty

import scala.meta._

/**
 * @author mbadgujar
 */
package object codegen {

  case class ActionConfig(name: String, @JsonProperty(required = true, value = "config_files") configFiles: Seq[String]) {
    def getFilePath(modulePackage: String) : String =
      modulePackage.replaceAll("\\.", "/") + "/" + name + ".scala"
  }

  case class ModuleDefinition(@JsonProperty(required = true, value = "dataset_type") datasetType: String,
                              @JsonProperty(required = true) imports: Seq[String],
                              @JsonProperty(required = true, value = "package") modulePackage: String,
                              @JsonProperty(required = true, value = "action_config") actionConfig: Seq[ActionConfig] ) {
    def getImports: Seq[Importer] = imports.map(imp => imp.parse[Importer].get)

    def getDatasetTypeName: Type.Name = Type.Name(datasetType)

    def getPackage: String = modulePackage
  }

  case class ActionsDefinitions(@JsonProperty(required = true) definitions: Seq[ActionDefinition],
                                @JsonProperty(required = true) imports: Seq[String]) {

    def getActionTypeListFn: Defn.Def = {
      val namedTypeExprs = definitions.map(_.getJacksonNamedType).toList
      q"override def getTypes: List[NamedType] = List(..$namedTypeExprs)"
    }

    def getCode(module: ModuleDefinition, className: String): String = {
      val packageTerms = module.getPackage.split("\\.")
      val packageStat = packageTerms.tail
        .foldLeft(None: Option[Term.Select]) {
          case (None, name) => Some(Term.Select(Term.Name(packageTerms.head), Term.Name(name)))
          case (Some(term), name) => Some(Term.Select(term, Term.Name(name)))
        }.get
      val coreImports = imports.map(_.parse[Importer].get)
      val importStats = (coreImports ++ module.getImports).map(imp => Import(List(imp))).toList
      val bodyStats = definitions.map { definition => definition.getClassDefn(module.getDatasetTypeName) }.toList
      val oName = Term.Name(className)
      val cName = Type.Name(className)

      q"""package $packageStat {
            ..$importStats
             object $oName {
             ..$bodyStats
             }
             class $cName extends TransformationTypeRegistration {
             import $oName._
               $getActionTypeListFn
             }
         }""".toString()
    }

    def merge(otherActionsDefinitions: ActionsDefinitions): ActionsDefinitions = {
       val mergedDefinitions = definitions ++ otherActionsDefinitions.definitions
       val mergedImports = imports ++ otherActionsDefinitions.imports
       ActionsDefinitions(mergedDefinitions.distinct, mergedImports.distinct)
    }

  }

  case class ActionDefinition(@JsonProperty(required = true) id: String,
                              @JsonProperty(required = true) params: List[ParamDefinition],
                              @JsonProperty(required = true) expr: String,
                              @JsonProperty(value = "is_multi_out", required = false) isMultiOutput: Option[Boolean]) {

    import ExprConstants._

    def getClassName: String = id.split("_").map(_.capitalize).mkString("", "", "Action")

    def getClassDefn(datasetType: Type.Name): Defn.Class = {
      val multiOuputFlag = isMultiOutput.getOrElse(false)
      val actionClassName = Type.Name(getClassName)
      val paramList = params.map(_.getParam)
      val datasetReferenceExpr = {
          val listTypeDatasetRef = params.filter(_.isDatasetReferenceList.getOrElse(false))
            .map(listRef => s"Option(${listRef.name}).getOrElse(Nil)")
          val datasetRef = params.filter(_.isDatasetReference.getOrElse(false)).map(_.name)

          {
            (listTypeDatasetRef, datasetRef) match {
              case (Nil, Nil) =>
                "Nil"
              case (_, Nil) =>
                listTypeDatasetRef.mkString(concatListExpr)
              case (Nil, _) =>
                datasetRef.mkString("Seq(", "," , ")")
              case (_, _) =>
                datasetRef.mkString("Seq(", "," , ")") + concatListExpr + listTypeDatasetRef.mkString(concatListExpr)
            }
          }.parse[Term].get
      }

      val actionExpr = expr.parse[Term].get
      if (multiOuputFlag) {
        val transformExtensionType = t"MultiOutputTransform[$datasetType]"
        q"""case class $actionClassName(..$paramList)
            extends $transformExtensionType($id) {
            override def transformFunctions(datasets: $datasetType*): Seq[TFunc[$datasetType]] = $actionExpr
            override def inputAliases: Seq[String] = $datasetReferenceExpr
          }"""
      } else {
        val transformExtensionType = t"SingleOutputTransform[$datasetType]"
        q"""case class $actionClassName(..$paramList)
            extends $transformExtensionType($id) {
            override def transformFunction(datasets: $datasetType*): TFunc[$datasetType] = $actionExpr
            override def inputAliases: Seq[String] = $datasetReferenceExpr
          }"""
      }
    }

    def getJacksonNamedType: Term.New = {
      val classTypeName = Type.Name(getClassName)
      val name = Lit.String(id)
      q"new NamedType(classOf[$classTypeName], $name)"
    }
  }

  case class ParamDefinition(@JsonProperty(required = true) name: String,
                             @JsonProperty(value = "type", required = true) paramType: String,
                             @JsonProperty(value = "mapped_name", required = false) mappedName: Option[String],
                             @JsonProperty(value = "is_required", required = false) isRequired: Option[Boolean],
                             @JsonProperty(value = "is_sql_expr", required = false) isSqlExpr: Option[Boolean],
                             @JsonProperty(value = "is_dataset_ref", required = false)
                             isDatasetReference: Option[Boolean],
                             @JsonProperty(value = "is_dataset_ref_list", required = false)
                             isDatasetReferenceList: Option[Boolean]) {

    import ExprConstants._
    def getJsonPropertyAnnotation(required: Boolean, name: Option[String] = None): Mod.Annot = {
      val requiredTerm = Term.Assign(Term.Name("required"), Lit.Boolean(required))
      val attrList = name.fold(List(requiredTerm)) {
        name => Nil :+ requiredTerm :+ Term.Assign(Term.Name("value"), Lit.String(name))
      }
      mod"@JsonProperty(..$attrList)"
    }

    def getParam: Term.Param = {
      val jsonPropertyAnnotation = getJsonPropertyAnnotation(isRequired.getOrElse(false), mappedName)
      val annotations = if (isSqlExpr.getOrElse(false))
        Nil :+ jsonPropertyAnnotation :+ sqlExprAnnotation
      else
        Nil :+ jsonPropertyAnnotation
      Term.Param(annotations, Type.Name(name), Some(paramType.parse[Type].get), None)
    }
  }

  object ExprConstants {
    val sqlExprAnnotation = mod"@SqlExpr"
    val concatListExpr = " ++ "
  }

}
