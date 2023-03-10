package com.rxhttp.compiler.ksp

import com.google.devtools.ksp.KSTypesNotPresentException
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.symbol.Modifier
import com.rxhttp.compiler.getKClassName
import com.rxhttp.compiler.isDependenceRxJava
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeVariableName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.TypeParameterResolver
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import com.squareup.kotlinpoet.ksp.toTypeVariableName
import rxhttp.wrapper.annotation.Parser
import java.util.*

/**
 * User: ljx
 * Date: 2021/10/17
 * Time: 22:33
 */
class ParserVisitor(
    private val logger: KSPLogger
) : KSVisitorVoid() {

    private val ksClassMap = LinkedHashMap<String, KSClassDeclaration>()
    private val classNameMap = LinkedHashMap<String, List<ClassName>>()

    @KspExperimental
    override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
        try {
            classDeclaration.checkParserValidClass()
            val annotation = classDeclaration.getAnnotationsByType(Parser::class).firstOrNull()
            var name = annotation?.name
            if (name.isNullOrBlank()) {
                name = classDeclaration.simpleName.toString()
            }
            ksClassMap[name] = classDeclaration
            val classNames =
                try {
                    annotation?.wrappers?.map { it.java.asClassName() }
                } catch (e: KSTypesNotPresentException) {
                    e.ksTypes.map {
                        ClassName.bestGuess(it.declaration.qualifiedName?.asString().toString())
                    }
                }
            classNames?.let { classNameMap[name] = it }

        } catch (e: NoSuchElementException) {
            logger.error(e, classDeclaration)
        }
    }

    @KspExperimental
    fun getFunList(codeGenerator: CodeGenerator): List<FunSpec> {
        val funList = ArrayList<FunSpec>()
        val rxHttpExtensions = RxHttpExtensions(logger)
        ksClassMap.forEach { (parserAlias, ksClass) ->
            rxHttpExtensions.generateRxHttpExtendFun(ksClass, parserAlias)
            if (isDependenceRxJava()) {
                //?????????RxJava????????????Java???????????????asXxx??????
                funList.addAll(ksClass.getAsXxxFun(parserAlias, classNameMap))
            }
        }
        rxHttpExtensions.generateClassFile(codeGenerator)
        return funList
    }
}

@KspExperimental
private fun KSClassDeclaration.getAsXxxFun(
    parserAlias: String,
    typeMap: LinkedHashMap<String, List<ClassName>>
): List<FunSpec> {

    val funList = arrayListOf<FunSpec>()
    val onParserFunReturnType = findOnParserFunReturnType() ?: return emptyList()
    val typeVariableNames = typeParameters.map { it.toTypeVariableName() }
    //??????public????????????
    for (constructor in getPublicConstructors()) {
        var typeCount = typeVariableNames.size
        if ("kotlin.Array<java.lang.reflect.Type>" ==
            constructor.parameters.firstOrNull()?.type?.toTypeName()?.toString()
        ) {
            //?????????Type???????????????????????????????????????
            typeCount = 1
        }
        if (constructor.parameters.size < typeCount) continue

        //?????????????????????????????????asXxx?????????????????????
        val parameterSpecs =
            constructor.getParameterSpecs(
                typeVariableNames,
                typeParameters.toTypeParameterResolver()
            )

        //?????????
        val funName = "as$parserAlias"

        //????????????(Observable<T>??????)
        val asFunReturnType = getKClassName("Observable").parameterizedBy(onParserFunReturnType)

        //?????????
        val funBody =
            "return asParser(%T(${
                getParamsName(constructor.parameters, parameterSpecs, typeVariableNames.size)
            }))"

        val funSpec = FunSpec.builder(funName)
            .addTypeVariables(typeVariableNames)
            .addParameters(parameterSpecs)
            .addStatement(funBody, toClassName())  //?????????
            .returns(asFunReturnType)
            .build()
            .apply { funList.add(this) }

        val haveClassTypeParam = parameterSpecs.any { p ->
            p.type.toString().startsWith("java.lang.Class")
        }

        if (haveClassTypeParam && typeVariableNames.size == 1) {
            //?????????Any????????????
            val nonAnyType = typeVariableNames.first().bounds.find { typeName ->
                val name = typeName.toString()
                name != "kotlin.Any" && name != "kotlin.Any?"
            }
            //???Class???????????? ??? ??????????????????1 ??????????????????????????????(Any??????????????????)???????????????Parser?????????wrappers???????????????asXxx??????
            if (nonAnyType == null) {
                constructor.getAsXxxFun(
                    parserAlias,
                    funSpec,
                    onParserFunReturnType,
                    typeMap,
                    funList
                )
            }
        }
    }

    return funList
}

/**
 * ??????Parser?????????wrappers????????????????????????asXxx??????
 * @param parserAlias ???????????????
 * @param funSpec ??????????????????asXxx?????????????????????wrappers??????????????????
 * @param onParserFunReturnType ????????????onParser?????????????????????
 * @param typeMap Parser?????????wrappers????????????
 * @param funList funList
 */
private fun KSFunctionDeclaration.getAsXxxFun(
    parserAlias: String,
    funSpec: FunSpec,
    onParserFunReturnType: TypeName,
    typeMap: LinkedHashMap<String, List<ClassName>>,
    funList: MutableList<FunSpec>
) {
    val parameterSpecs = funSpec.parameters
    val typeVariableNames = funSpec.typeVariables

    val type = ClassName("java.lang.reflect", "Type")
    val parameterizedType = ClassName("rxhttp.wrapper.entity", "ParameterizedTypeImpl")

    val wrapperListClass = arrayListOf<ClassName>()
    typeMap[parserAlias]?.apply { wrapperListClass.addAll(this) }
    if (LIST !in wrapperListClass) {
        wrapperListClass.add(0, LIST)
    }
    wrapperListClass.forEach { wrapperClass ->

        //1???asXxx???????????????
        val onParserFunReturnWrapperType =
            if (onParserFunReturnType is ParameterizedTypeName) { // List<T>, Map<K,V>?????????????????????
                //???????????????n?????????????????????????????????????????????
                val typeNames = onParserFunReturnType.typeArguments.map { typeArg ->
                    wrapperClass.parameterizedBy(typeArg)
                }
                onParserFunReturnType.rawType.parameterizedBy(*typeNames.toTypedArray())
            } else {
                wrapperClass.parameterizedBy(onParserFunReturnType.copy(false))
            }
        val asFunReturnType =
            getKClassName("Observable").parameterizedBy(onParserFunReturnWrapperType.copy(onParserFunReturnType.isNullable))

        //2???asXxx?????????
        val name = wrapperClass.toString()
        val simpleName = name.substring(name.lastIndexOf(".") + 1)
        val funName = "as$parserAlias${simpleName}"

        //3???asXxx?????????
        val funBody = CodeBlock.builder()
        val paramsName = StringBuilder()
        //??????????????????????????????
        parameterSpecs.forEachIndexed { index, param ->
            if (index > 0) paramsName.append(", ")
            if (param.type.toString().startsWith("java.lang.Class")) {
                /*
                 * Class???????????????????????????????????????????????????????????????
                 * ?????????val tTypeList = ParameterizedTypeImpl.get(List.class, tType)
                 */
                val variableName = "${param.name}$simpleName"
                val expression =
                    "val $variableName = %T.get($simpleName::class.java, ${param.name})"
                funBody.addStatement(expression, parameterizedType)
                val parameterType = parameters[index].name?.asString()
                if ("java.lang.reflect.Type[]" == parameterType.toString()) {
                    paramsName.append("new Type[]{$variableName}")
                } else {
                    paramsName.append(variableName)
                }
            } else {
                if (KModifier.VARARG in param.modifiers) paramsName.append("*")
                paramsName.append(param.name)
            }
        }
        val returnStatement = "return asParser(%T($paramsName))"
        funBody.addStatement(
            returnStatement, (parent as KSClassDeclaration).toClassName()
        )

        //4?????????asXxx??????
        FunSpec.builder(funName)
            .addTypeVariables(typeVariableNames)
            .addParameters(funSpec.parameters)
            .addCode(funBody.build())  //????????????????????????
            .returns(asFunReturnType)
            .build()
            .apply { funList.add(this) }
    }
}

//??????onParser??????????????????
private fun KSClassDeclaration.findOnParserFunReturnType(): TypeName? {
    val ksFunction = getAllFunctions().find {
        it.isPublic() &&
                !it.modifiers.contains(Modifier.JAVA_STATIC) &&
                it.getFunName() == "onParse" &&
                it.parameters.size == 1 &&
                it.parameters[0].type.getQualifiedName() == "okhttp3.Response"
    }
    return ksFunction?.returnType?.toTypeName(typeParameters.toTypeParameterResolver())
}


@KspExperimental
private fun KSFunctionDeclaration.getParameterSpecs(
    typeVariableNames: List<TypeVariableName>,
    parent: TypeParameterResolver? = null,
): List<ParameterSpec> {
    val parameterList = ArrayList<ParameterSpec>()
    var typeIndex = 0
    val className = Class::class.asClassName()
    parameters.forEach { ksValueParameter ->
        val variableType = ksValueParameter.type.getQualifiedName()
        if (variableType.toString() == "java.lang.reflect.Type[]") {
            typeVariableNames.forEach { typeVariableName ->
                //Type???????????????Class<T>??????
                val classTypeName = className.parameterizedBy(typeVariableName)
                val variableName =
                    "${typeVariableName.name.lowercase(Locale.getDefault())}Type"
                val parameterSpec =
                    ParameterSpec.builder(variableName, classTypeName).build()
                parameterList.add(parameterSpec)
            }
        } else if (variableType.toString() == "java.lang.reflect.Type"
            && typeIndex < typeVariableNames.size
        ) {
            //Type???????????????Class<T>??????
            val classTypeName = className.parameterizedBy(typeVariableNames[typeIndex++])
            val variableName = ksValueParameter.name?.asString().toString()
            val parameterSpec =
                ParameterSpec.builder(variableName, classTypeName).build()
            parameterList.add(parameterSpec)
        } else {
            val functionTypeParams = typeParameters.toTypeParameterResolver(parent)
            ksValueParameter.toKParameterSpec(functionTypeParams).apply {
                parameterList.add(this)
            }
        }
    }
    return parameterList
}

/**
 * @param variableElements ?????????????????????????????????
 * @param parameterSpecs ???????????????????????????????????????????????????????????????????????????parameterSpecs.size() >= variableElements.size()
 * @param typeCount ?????????????????????
 */
private fun getParamsName(
    variableElements: List<KSValueParameter>,
    parameterSpecs: List<ParameterSpec>,
    typeCount: Int
): String {
    val sb = StringBuilder()
    var paramIndex = 0
    var variableIndex = 0
    val variableSize = variableElements.size
    val paramSize = parameterSpecs.size
    while (paramIndex < paramSize && variableIndex < variableSize) {
        if (variableIndex > 0) sb.append(", ")
        val type = variableElements[variableIndex++].type.getQualifiedName()
        if ("java.lang.reflect.Type[]" == type.toString()) {
            sb.append("new Type[]{")
            for (i in 0 until typeCount) {
                if (i > 0) sb.append(", ")
                sb.append(parameterSpecs[paramIndex++].name)
            }
            sb.append("}")
        } else {
            val parameterSpec = parameterSpecs[paramIndex++]
            if (KModifier.VARARG in parameterSpec.modifiers) sb.append("*")
            sb.append(parameterSpec.name)
        }
    }
    return sb.toString()
}


@Throws(NoSuchElementException::class)
private fun KSClassDeclaration.checkParserValidClass() {
    val elementQualifiedName = qualifiedName?.asString()
    if (!isPublic()) {
        throw NoSuchElementException("The class '$elementQualifiedName' must be public")
    }
    if (isAbstract()) {
        val msg =
            "The class '$elementQualifiedName' is abstract. You can't annotate abstract classes with @${Parser::class.java.simpleName}"
        throw NoSuchElementException(msg)
    }

    val typeParameterList = typeParameters
    if (typeParameterList.isNotEmpty()) {
        //????????? java.lang.reflect.Type ?????????????????????
        val constructorFun = getPublicConstructors().filter { it.parameters.isNotEmpty() }
        val typeArgumentConstructorFun = constructorFun
            .findTypeArgumentConstructorFun(typeParameterList.size)
        if (typeArgumentConstructorFun == null) {
            val funBody = StringBuffer("public ${simpleName.asString()}(")
            for (i in typeParameterList.indices) {
                funBody.append("java.lang.reflect.Type")
                funBody.append(if (i == typeParameterList.lastIndex) ")" else ",")
            }
            val msg =
                "This class '$elementQualifiedName' must declare '$funBody' constructor fun"
            throw NoSuchElementException(msg)
        }
    }

    val className = "rxhttp.wrapper.parse.Parser"
    if (!instanceOf(className)) {
        val msg =
            "The class '$elementQualifiedName' annotated with @${Parser::class.java.simpleName} must inherit from $className"
        throw NoSuchElementException(msg)
    }
}