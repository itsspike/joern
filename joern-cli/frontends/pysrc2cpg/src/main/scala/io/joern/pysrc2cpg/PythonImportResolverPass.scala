package io.joern.pysrc2cpg

import better.files.File
import io.joern.x2cpg.passes.frontend.XImportResolverPass
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.language.importresolver.*

import java.io.File as JFile
import java.util.regex.Matcher
import scala.collection.mutable

/** Attempts to solve imports similar Python, and will use heuristics to build out unresolved imports.
  *
  * @see
  *   <a href="https://docs.python.org/3/reference/import.html#searching">Python 3 Import Searching Reference</a>
  */
class PythonImportResolverPass(cpg: Cpg) extends XImportResolverPass(cpg) {

  /** Stores all CPG entities with their associated Pythonic import paths as keys.
    */
  private val moduleCache: mutable.HashMap[String, ImportableEntity] = mutable.HashMap.empty

  override def init(): Unit = {
    cpg.typeDecl.isExternal(false).nameExact("<module>").foreach { moduleType =>
      val modulePath = fileToPythonImportNotation(moduleType.filename)
      cpg.method.fullNameExact(moduleType.fullName).headOption.foreach { moduleMethod =>
        moduleCache.put(modulePath, Module(moduleType, moduleMethod))
        moduleMethod.astChildren.foreach {
          case moduleFunction: Method =>
            moduleCache.put(s"$modulePath.${moduleFunction.name}", ImportableFunction(moduleFunction))
          // Ignore types for functions that are used for method pointers
          case moduleType: TypeDecl if moduleMethod.astChildren.isMethod.fullNameExact(moduleType.fullName).isEmpty =>
            moduleCache.put(s"$modulePath.${moduleType.name}", ImportableType(moduleType))
          case _ => // do nothing
        }
      }
      moduleType.member.foreach { moduleMember =>
        moduleCache
          .getOrElseUpdate(s"$modulePath.${moduleMember.name}", ModuleVariable(moduleType.fullName, moduleMember))
      }
    }
  }

  private def fileToPythonImportNotation(filename: String): String =
    filename
      .stripPrefix(codeRootDir)
      .replaceAll(Matcher.quoteReplacement(JFile.separator), ".")
      .stripSuffix(".py")
      .stripSuffix(".__init__")

  override protected def optionalResolveImport(
    fileName: String,
    importCall: Call,
    importedEntity: String,
    importedAs: String,
    diffGraph: DiffGraphBuilder
  ): Unit = {
    val currDir = File(codeRootDir) / fileName match
      case x if x.isDirectory => x
      case x                  => x.parent

    val importedEntityAsFullyQualifiedImport =
      // If the path/entity uses Python's `from .import x` syntax, we will need to remove these
      fileToPythonImportNotation(importedEntity.replaceFirst("^\\.+", ""))
    val importedEntityAsRelativeImport = Seq(
      fileToPythonImportNotation(currDir.pathAsString.stripPrefix(codeRootDir).stripPrefix(JFile.separator)),
      importedEntityAsFullyQualifiedImport
    ).filterNot(_.isBlank).mkString(".")

    // We evaluated both variations, based on what we could expect from different versions of Python and how the package
    // layout is interpreted by the presence of lack of `__init__.py` files. Additionally, external packages are always
    // fully qualified.
    val resolvedImports =
      Seq(
        moduleCache.get(importedEntityAsRelativeImport),
        moduleCache.get(importedEntityAsFullyQualifiedImport)
      ).flatten.flatMap(_.toResolvedImport(importedAs))

    if (resolvedImports.nonEmpty) {
      // The import was resolved to an entity successfully
      resolvedImports.foreach(x => evaluatedImportToTag(x, importCall, diffGraph))
    } else {
      // Here we use heuristics to guess the correct paths, and make the types look friendly for querying
      unresolvableImportToUnknownImport(currDir, fileName, importedEntity, importedAs)
        .foreach(x => evaluatedImportToTag(x, importCall, diffGraph))
    }
  }

  private def unresolvableImportToUnknownImport(
    currDir: File,
    currFileName: String,
    importedEntity: String,
    importedAs: String
  ): Set[EvaluatedImport] = {
    val (namespace, entityName) = if (importedEntity.contains(".")) {
      val splitName = importedEntity.split('.').toSeq
      val namespace = importedEntity.stripSuffix(s".${splitName.last}")
      (relativizeNamespace(namespace, currFileName), splitName.last)
    } else {
      val relCurrDir = currDir.pathAsString.stripPrefix(codeRootDir).stripPrefix(JFile.separator)

      (relCurrDir, importedEntity)
    }

    createPseudoImports(namespace, entityName, importedAs)
  }

  private def relativizeNamespace(path: String, fileName: String): String = if (path.startsWith(".")) {
    // TODO: pysrc2cpg does not link files to the correct namespace nodes
    val sep = Matcher.quoteReplacement(JFile.separator)
    // The below gives us the full path of the relative "."
    val relativeNamespace =
      if (fileName.contains(JFile.separator))
        fileName.substring(0, fileName.lastIndexOf(JFile.separator)).replaceAll(sep, ".")
      else ""
    (if (path.length > 1) relativeNamespace + path.replaceAll(sep, ".")
     else relativeNamespace).stripPrefix(".")
  } else path

  /** For an unresolveable import, create a best-effort path of what could be imported, as well as what kind of entity
    * may be imported.
    *
    * @param path
    *   the module path.
    * @param expEntity
    *   the name of the imported entity. This could be a function, module, or variable/field.
    * @param alias
    *   how the imported entity is named.
    * @return
    *   the possible callee names
    */
  private def createPseudoImports(path: String, expEntity: String, alias: String): Set[EvaluatedImport] = {
    val pathSep            = "."
    val sep                = Matcher.quoteReplacement(JFile.separator)
    val isMaybeConstructor = expEntity.split("\\.").lastOption.exists(s => s.nonEmpty && s.charAt(0).isUpper)

    def toUnresolvedImport(pseudoPath: String): Set[EvaluatedImport] = {
      if (isMaybeConstructor) {
        Set(UnknownMethod(Seq(pseudoPath, "__init__").mkString(pathSep), alias), UnknownTypeDecl(pseudoPath))
      } else {
        Set(UnknownImport(pseudoPath))
      }
    }

    if (path.isBlank) {
      if (expEntity.contains(".")) {
        // Case 1: Qualified path: import foo.bar
        val splitFunc = expEntity.split("\\.")
        val name      = splitFunc.tail.mkString(".")
        toUnresolvedImport(s"${splitFunc(0)}.py:<module>$pathSep$name")
      } else {
        // Case 2: import of a module: import foo => foo.py
        toUnresolvedImport(s"$expEntity.py:<module>")
      }
    } else {
      // Case 3: Import from module using alias, e.g. import bar from foo as faz
      toUnresolvedImport(s"${path.replaceAll("\\.", sep)}.py:<module>$pathSep$expEntity")
    }
  }

  private sealed trait ImportableEntity {

    def toResolvedImport(alias: String): List[EvaluatedImport]

  }

  private case class Module(moduleType: TypeDecl, moduleMethod: Method) extends ImportableEntity {
    override def toResolvedImport(alias: String): List[EvaluatedImport] =
      List(ResolvedTypeDecl(moduleType.fullName), ResolvedMethod(moduleMethod.fullName, moduleMethod.name))

  }

  private case class ModuleVariable(baseTypeFullName: String, member: Member) extends ImportableEntity {

    override def toResolvedImport(alias: String): List[EvaluatedImport] = List(
      ResolvedMember(baseTypeFullName, member.name)
    )
  }

  private case class ImportableFunction(function: Method) extends ImportableEntity {
    override def toResolvedImport(alias: String): List[EvaluatedImport] = List(ResolvedMethod(function.fullName, alias))
  }

  private case class ImportableType(typ: TypeDecl) extends ImportableEntity {
    override def toResolvedImport(alias: String): List[EvaluatedImport] =
      List(ResolvedTypeDecl(typ.fullName), ResolvedMethod(s"${typ.fullName}.__init__", typ.name))
  }
}
