/* Copyright (C) 2019-2023 Julian Valentin, LTeX Development Community
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package org.bsplines.ltexls.server

import org.bsplines.ltexls.parsing.AnnotatedTextFragment
import org.bsplines.ltexls.parsing.CodeAnnotatedTextBuilder
import org.bsplines.ltexls.parsing.CodeFragment
import org.bsplines.ltexls.parsing.CodeFragmentizer
import org.bsplines.ltexls.settings.SettingsManager
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.Position
import org.languagetool.DetectedLanguage
import org.languagetool.language.identifier.SimpleLanguageIdentifier
import org.languagetool.markup.AnnotatedText
import java.io.OutputStream
import java.io.PrintStream

class CompletionListProvider(
  val settingsManager: SettingsManager,
) {
  private var simpleLanguageIdentifier: SimpleLanguageIdentifier

  init {
    // workaround bugs like https://github.com/languagetool-org/languagetool/issues/3181,
    // in which LT prints to stdout instead of stderr (this messes up the LSP communication
    // and results in a deadlock) => temporarily discard output to stdout
    val stdout: PrintStream = System.out
    System.setOut(
      PrintStream(
        object : OutputStream() {
          override fun write(b: Int) {
          }
        },
        false,
        "utf-8",
      ),
    )
    simpleLanguageIdentifier = SimpleLanguageIdentifier()
    System.setOut(stdout)
  }

  private val fullCompletionListMap: MutableMap<String, List<String>> = HashMap()

  fun createCompletionList(
    document: LtexTextDocumentItem,
    position: Position,
  ): CompletionList {
    val codeFragmentPositionPair: Pair<CodeFragment, Int> =
      getCodeFragmentFromPosition(document, position) ?: return CompletionList(emptyList())

    val annotatedTextFragment: AnnotatedTextFragment =
      buildAnnotatedTextFragment(document, codeFragmentPositionPair.first)

    val languageShortCode: String =
      getLanguageShortCode(annotatedTextFragment)
        ?: return CompletionList(emptyList())

    val prefix: String =
      getPrefixFromPosition(
        annotatedTextFragment.codeFragment.code,
        codeFragmentPositionPair.second,
      )
    if (prefix.isEmpty()) return CompletionList(emptyList())

    val fullCompletionList: List<String> = getFullCompletionList(languageShortCode)
    if (fullCompletionList.isEmpty()) return CompletionList(emptyList())

    val completionList = ArrayList<CompletionItem>()

    for (entry: String in annotatedTextFragment.codeFragment.settings.dictionary) {
      if (entry.startsWith(prefix)) completionList.add(CompletionItem(entry))
    }

    for (entry: String in fullCompletionList) {
      if (entry.startsWith(prefix)) completionList.add(CompletionItem(entry))
    }

    return CompletionList(completionList)
  }

  private fun getCodeFragmentFromPosition(
    document: LtexTextDocumentItem,
    position: Position,
  ): Pair<CodeFragment, Int>? {
    val codeFragmentizer: CodeFragmentizer = CodeFragmentizer.create(document.languageId)
    val code: String = document.text
    val codeFragments: List<CodeFragment> =
      codeFragmentizer.fragmentize(code, this.settingsManager.settings)
    val pos: Int = document.convertPosition(position)

    var matchingCodeFragment: CodeFragment? = null

    for (codeFragment: CodeFragment in codeFragments) {
      if (
        (codeFragment.fromPos <= pos) && (pos < codeFragment.fromPos + codeFragment.code.length)
      ) {
        if (
          (matchingCodeFragment == null) ||
          (codeFragment.fromPos > matchingCodeFragment.fromPos)
        ) {
          matchingCodeFragment = codeFragment
        }
      }
    }

    return if (matchingCodeFragment != null) {
      Pair(matchingCodeFragment, pos - matchingCodeFragment.fromPos)
    } else {
      null
    }
  }

  private fun buildAnnotatedTextFragment(
    document: LtexTextDocumentItem,
    codeFragment: CodeFragment,
  ): AnnotatedTextFragment {
    val builder: CodeAnnotatedTextBuilder =
      CodeAnnotatedTextBuilder.create(codeFragment.codeLanguageId)
    builder.setSettings(codeFragment.settings)
    builder.addCode(codeFragment.code)
    val annotatedText: AnnotatedText = builder.build()
    return AnnotatedTextFragment(annotatedText, codeFragment, document)
  }

  private fun getLanguageShortCode(annotatedTextFragment: AnnotatedTextFragment): String? =
    if (annotatedTextFragment.codeFragment.settings.languageShortCode == "auto") {
      val cleanText: String =
        this.simpleLanguageIdentifier.cleanAndShortenText(
          annotatedTextFragment.annotatedText.plainText,
        )
      val detectedLanguage: DetectedLanguage? =
        this.simpleLanguageIdentifier.detectLanguage(cleanText, emptyList(), emptyList())
      detectedLanguage?.detectedLanguage?.shortCodeWithCountryAndVariant
    } else {
      annotatedTextFragment.codeFragment.settings.languageShortCode
    }

  private fun getPrefixFromPosition(
    code: String,
    pos: Int,
  ): String {
    if (pos >= code.length) return ""

    for (curPos: Int in pos - 1 downTo 0) {
      val character: Char = code[curPos]
      if (!character.isLetter() && (character != '-')) return code.substring(curPos + 1, pos)
    }

    return code.substring(0, pos)
  }

  private fun getFullCompletionList(languageShortCode: String): List<String> {
    val fullCompletionList: List<String> =
      fullCompletionListMap[languageShortCode] ?: run {
        if (LANGUAGE_SHORT_CODE_REGEX.matches(languageShortCode)) {
          val completionListText: String =
            javaClass.getResource("/completionList.$languageShortCode.txt")?.readText()?.trim()
              ?: ""

          val fullCompletionList: List<String> =
            if (completionListText.isNotEmpty()) {
              completionListText.split('\n')
            } else {
              emptyList()
            }

          fullCompletionListMap[languageShortCode] = fullCompletionList
          fullCompletionList
        } else {
          emptyList()
        }
      }

    return fullCompletionList
  }

  companion object {
    private val LANGUAGE_SHORT_CODE_REGEX = Regex("^[-A-Za-z]+$")
  }
}
