package com.itangcent.idea.utils

import com.google.inject.Inject
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.itangcent.common.constant.Attrs
import com.itangcent.common.utils.asBool
import com.itangcent.common.utils.sub
import com.itangcent.idea.plugin.api.export.AdditionalField
import com.itangcent.idea.plugin.api.export.core.AdditionalParseHelper
import com.itangcent.idea.plugin.api.export.core.ClassExportRuleKeys
import com.itangcent.idea.plugin.api.export.yapi.YapiClassExportRuleKeys
import com.itangcent.intellij.config.ConfigReader
import com.itangcent.intellij.config.rule.RuleComputeListener
import com.itangcent.intellij.config.rule.RuleContext
import com.itangcent.intellij.config.rule.RuleKey
import com.itangcent.intellij.config.rule.computer
import com.itangcent.intellij.extend.guice.PostConstruct
import com.itangcent.intellij.jvm.JsonOption
import com.itangcent.intellij.jvm.JsonOption.has
import com.itangcent.intellij.jvm.duck.DuckType
import com.itangcent.intellij.jvm.duck.SingleDuckType
import com.itangcent.intellij.jvm.element.ExplicitClass
import com.itangcent.intellij.jvm.element.ExplicitElement
import com.itangcent.intellij.jvm.element.ExplicitMethod
import com.itangcent.intellij.psi.ClassRuleKeys
import com.itangcent.intellij.psi.DefaultPsiClassHelper
import com.itangcent.intellij.psi.ResolveContext
import java.util.*

/**
 * support config:
 * 1. json.cache.disable
 * support rules:
 * 1. field.parse.before
 * 2. field.parse.after
 */
open class ContextualPsiClassHelper : DefaultPsiClassHelper() {

    @Inject
    protected lateinit var configReader: ConfigReader

    @Inject(optional = true)
    private val ruleComputeListener: RuleComputeListener? = null

    @Inject
    private lateinit var additionalParseHelper: AdditionalParseHelper

    private val parseContext: ThreadLocal<Deque<String>> = ThreadLocal()
    private val parseScriptContext = ParseScriptContextImpl()

    @PostConstruct
    fun initRuleComputeListener() {
        (ruleComputeListener as? RuleComputeListenerRegistry)?.register(InnerComputeListener())
    }

    override fun beforeParseClass(psiClass: PsiClass, resolveContext: ResolveContext, fields: MutableMap<String, Any?>) {
        tryInitParseContext()
        ruleComputer.computer(ClassExportRuleKeys.JSON_CLASS_PARSE_BEFORE, psiClass)
        super.beforeParseClass(psiClass, resolveContext, fields)
    }

    override fun beforeParseType(
        psiClass: PsiClass,
        duckType: SingleDuckType,
        resolveContext: ResolveContext,
        fields: MutableMap<String, Any?>,
    ) {
        tryInitParseContext()
        ruleComputer.computer(ClassExportRuleKeys.JSON_CLASS_PARSE_BEFORE, duckType, psiClass)
        super.beforeParseType(psiClass, duckType, resolveContext, fields)
    }

    override fun afterParseClass(psiClass: PsiClass, resolveContext: ResolveContext, fields: MutableMap<String, Any?>) {
        try {
            super.afterParseClass(psiClass, resolveContext, fields)
            computeAdditionalField(psiClass, resolveContext, fields)
            ruleComputer.computer(ClassExportRuleKeys.JSON_CLASS_PARSE_AFTER, psiClass)
        } finally {
            tryCleanParseContext()
        }
    }

    override fun afterParseType(
        psiClass: PsiClass,
        duckType: SingleDuckType,
        resolveContext: ResolveContext,
        fields: MutableMap<String, Any?>,
    ) {
        try {
            super.afterParseType(psiClass, duckType, resolveContext, fields)
            computeAdditionalField(psiClass, resolveContext, fields)
            ruleComputer.computer(ClassExportRuleKeys.JSON_CLASS_PARSE_AFTER, duckType, psiClass)
        } finally {
            tryCleanParseContext()
        }
    }

    protected open fun tryInitParseContext() {
        if (parseContext.get() == null) {
            parseContext.set(LinkedList())
            clearCachePotentially()
        }
    }

    protected open fun tryCleanParseContext() {
        val context = parseContext.get()
        if (context.isNullOrEmpty()) {
            parseContext.remove()
            clearCachePotentially()
        }
    }

    private fun clearCachePotentially() {
        if (configReader.first("json.cache.disable").asBool() == true) {
            devEnv?.dev {
                logger.info("clear json cache")
            }
            resolvedInfo.clear()
        }
    }

    override fun beforeParseFieldOrMethod(
        fieldName: String,
        fieldType: DuckType,
        fieldOrMethod: ExplicitElement<*>,
        resourcePsiClass: ExplicitClass,
        resolveContext: ResolveContext,
        fields: MutableMap<String, Any?>,
    ): Boolean {
        pushField(fieldName)
        if (fieldOrMethod is ExplicitMethod) {
            ruleComputer.computer(ClassExportRuleKeys.JSON_METHOD_PARSE_BEFORE, fieldOrMethod)
        } else {
            ruleComputer.computer(ClassExportRuleKeys.JSON_FIELD_PARSE_BEFORE, fieldOrMethod)
        }

        return super.beforeParseFieldOrMethod(fieldName, fieldType, fieldOrMethod, resourcePsiClass, resolveContext, fields)
    }

    private fun pushField(fieldName: String) {
        parseContext.get()?.add(fieldName)
        devEnv?.dev {
            logger.info("path -> ${parseScriptContext.path()}")
        }
    }

    override fun onIgnoredParseFieldOrMethod(
        fieldName: String,
        fieldType: DuckType,
        fieldOrMethod: ExplicitElement<*>,
        resourcePsiClass: ExplicitClass,
        resolveContext: ResolveContext,
        fields: MutableMap<String, Any?>,
    ) {
        super.onIgnoredParseFieldOrMethod(fieldName, fieldType, fieldOrMethod, resourcePsiClass, resolveContext, fields)
        popField(fieldName)
    }

    override fun afterParseFieldOrMethod(
        fieldName: String,
        fieldType: DuckType,
        fieldOrMethod: ExplicitElement<*>,
        resourcePsiClass: ExplicitClass,
        resolveContext: ResolveContext,
        fields: MutableMap<String, Any?>,
    ) {
        super.afterParseFieldOrMethod(fieldName, fieldType, fieldOrMethod, resourcePsiClass, resolveContext, fields)

        if (fieldOrMethod is ExplicitMethod) {
            ruleComputer.computer(ClassExportRuleKeys.JSON_METHOD_PARSE_AFTER, fieldOrMethod)
        } else {
            ruleComputer.computer(ClassExportRuleKeys.JSON_FIELD_PARSE_AFTER, fieldOrMethod)
        }
        popField(fieldName)
        computeAdditionalField(fieldOrMethod.psi(), resolveContext, fields)
    }

    protected open fun computeAdditionalField(
        context: PsiElement,
        resolveContext: ResolveContext,
        fields: MutableMap<String, Any?>,
    ) {
        //support json.additional.field
        val additionalFields = ruleComputer.computer(ClassExportRuleKeys.JSON_ADDITIONAL_FIELD, context)
        if (!additionalFields.isNullOrBlank()) {
            for (additionalField in additionalFields.lines()) {
                val field = additionalParseHelper.parseFieldFromJson(additionalField)
                if (field.name.isNullOrBlank()
                    || field.type.isNullOrBlank()
                ) {
                    logger.error("Illegal additional field: $additionalField")
                    return
                }
                val fieldName = field.name
                if (fields.containsKey(fieldName)) {
                    logger.debug("additional field [$fieldName] is already existed.")
                    continue
                }
                resolveAdditionalField(field, context, resolveContext, fields)
            }
        }
    }

    protected open fun resolveAdditionalField(
        additionalField: AdditionalField,
        context: PsiElement,
        resolveContext: ResolveContext,
        fields: MutableMap<String, Any?>,
    ) {
        val additionalFieldType = duckTypeHelper!!.resolve(additionalField.type!!, context)
        val fieldName = additionalField.name!!
        if (additionalFieldType == null) {
            fields[fieldName] = null
        } else {
            fields[fieldName] = doGetTypeObject(additionalFieldType, context, resolveContext.next())
        }
        if (resolveContext.option.has(JsonOption.READ_COMMENT)) {
            fields.sub(Attrs.COMMENT_ATTR)[fieldName] = additionalField.desc
        }
    }

    private fun popField(fieldName: String) {
        parseContext.get()?.removeLast()
        devEnv?.dev {
            logger.info("path -> ${parseScriptContext.path()}")
        }
    }

    inner class ParseScriptContextImpl : ParseScriptContext {
        override fun path(): String {
            return parseContext.get()?.joinToString(".") ?: ""
        }

        override fun property(property: String): String {
            val context = parseContext.get()
            return if (context.isNullOrEmpty()) {
                property
            } else {
                "${context.joinToString(".")}.$property"
            }
        }
    }

    inner class InnerComputeListener : RuleComputeListener {

        override fun computer(
            ruleKey: RuleKey<*>,
            target: Any,
            context: PsiElement?,
            contextHandle: (RuleContext) -> Unit,
            methodHandle: (RuleKey<*>, Any, PsiElement?, (RuleContext) -> Unit) -> Any?,
        ): Any? {
            return if (JSON_RULE_KEYS.contains(ruleKey)) {
                methodHandle(ruleKey, target, context) {
                    contextHandle(it)
                    it.setExt("fieldContext", parseScriptContext)
                }
            } else {
                methodHandle(ruleKey, target, context, contextHandle)
            }
        }
    }

    companion object {
        val JSON_RULE_KEYS = arrayOf(
            ClassRuleKeys.FIELD_IGNORE,
            ClassRuleKeys.FIELD_DOC,
            ClassRuleKeys.FIELD_NAME,
            ClassRuleKeys.FIELD_NAME_PREFIX,
            ClassRuleKeys.FIELD_NAME_SUFFIX,
            ClassRuleKeys.JSON_UNWRAPPED,
            YapiClassExportRuleKeys.FIELD_MOCK,
            YapiClassExportRuleKeys.FIELD_ADVANCED,
            ClassExportRuleKeys.FIELD_DEMO,
            ClassExportRuleKeys.FIELD_DEFAULT_VALUE,
            ClassExportRuleKeys.JSON_FIELD_PARSE_BEFORE,
            ClassExportRuleKeys.JSON_FIELD_PARSE_AFTER,
            ClassExportRuleKeys.FIELD_REQUIRED
        )
    }
}

interface ParseScriptContext {
    fun path(): String
    fun property(property: String): String
}
