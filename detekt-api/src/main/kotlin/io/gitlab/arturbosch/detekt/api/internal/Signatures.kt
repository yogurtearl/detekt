package io.gitlab.arturbosch.detekt.api.internal

import io.github.detekt.psi.fileName
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffsetSkippingComments
import java.io.File

private val multipleWhitespaces = Regex("\\s{2,}")

internal fun PsiElement.searchName(): String {
    return this.namedUnwrappedElement?.name?.formatElementName() ?: "<UnknownName>"
}

/*
 * PsiElements of type KtFile use the absolute path as their names. This resulted in
 * having two absolute paths being printed along with the output: one for the name
 * and one for the location. Consequently, IntelliJ was not hyperlinking them because
 * it only hyperlinks one path file per line.
 *
 * This gets the file name only, instead of the entire absolute path, and takes it as the element name.
 *
 * Example: KtFile with name /full/path/to/Test.kt will have its name formatted to be simply Test.kt
 */
private fun String.formatElementName(): String =
    if (contains(File.separatorChar)) substringAfterLast(File.separatorChar)
    else this

/*
 * KtCompiler wrongly used Path.filename as the name for a KtFile instead of the whole path.
 * This resulted into the question "How do we get the absolute path from a KtFile?".
 * Fixing this problem, we do not need KtFile.absolutePath anymore.
 *
 * Fixing the filename will change all baseline signatures.
 * Therefore we patch the signature here to restore the old behavior.
 *
 * Fixing the baseline will need a new major release - #2680.
 */
internal fun PsiElement.buildFullSignature(): String {
    var fullSignature = this.searchSignature()
    val parentSignatures = this.parents
        .filter { it is KtClassOrObject }
        .map { it.extractClassName() }
        .toList()
        .reversed()
        .joinToString(".")

    if (parentSignatures.isNotEmpty()) {
        fullSignature = "$parentSignatures\$$fullSignature"
    }

    val filename = this.containingFile.fileName
    if (!fullSignature.startsWith(filename)) {
        fullSignature = "$filename\$$fullSignature"
    }

    return fullSignature
}

private fun PsiElement.extractClassName() =
    this.getNonStrictParentOfType<KtClassOrObject>()?.nameAsSafeName?.asString().orEmpty()

private fun PsiElement.searchSignature(): String {
    return when (this) {
        is KtNamedFunction -> buildFunctionSignature(this)
        is KtClassOrObject -> buildClassSignature(this)
        is KtFile -> fileSignature()
        else -> this.text
    }.replace('\n', ' ').replace(multipleWhitespaces, " ")
}

private fun KtFile.fileSignature() = "${this.packageFqName.asString()}.${this.fileName}"

private fun buildClassSignature(classOrObject: KtClassOrObject): String {
    var baseName = classOrObject.nameAsSafeName.asString()
    val typeParameters = classOrObject.typeParameters
    if (typeParameters.size > 0) {
        baseName += "<"
        baseName += typeParameters.joinToString(", ") { it.text }
        baseName += ">"
    }
    val extendedEntries = classOrObject.superTypeListEntries
    if (extendedEntries.isNotEmpty()) baseName += " : "
    extendedEntries.forEach { baseName += it.typeAsUserType?.referencedName.orEmpty() }
    return baseName
}

private fun buildFunctionSignature(element: KtNamedFunction): String {
    val startOffset = element.startOffsetSkippingComments - element.startOffset
    val endOffset = if (element.typeReference != null) {
        element.typeReference?.endOffset ?: 0
    } else {
        element.valueParameterList?.endOffset ?: 0
    } - element.startOffset

    require(startOffset < endOffset) {
        "Error building function signature with range $startOffset - $endOffset for element: ${element.text}"
    }
    return element.text.substring(startOffset, endOffset)
}
