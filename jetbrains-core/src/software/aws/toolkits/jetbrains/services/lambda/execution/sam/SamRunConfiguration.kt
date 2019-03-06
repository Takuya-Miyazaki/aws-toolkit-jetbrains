// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.execution.sam

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RefactoringListenerProvider
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.listeners.RefactoringElementAdapter
import com.intellij.refactoring.listeners.RefactoringElementListener
import software.amazon.awssdk.services.lambda.model.Runtime
import software.aws.toolkits.core.credentials.CredentialProviderNotFound
import software.aws.toolkits.core.credentials.ToolkitCredentialsProvider
import software.aws.toolkits.core.region.AwsRegion
import software.aws.toolkits.jetbrains.core.credentials.CredentialManager
import software.aws.toolkits.jetbrains.core.credentials.ProjectAccountSettingsManager
import software.aws.toolkits.jetbrains.core.region.AwsRegionProvider
import software.aws.toolkits.jetbrains.services.lambda.HandlerCompletionProvider
import software.aws.toolkits.jetbrains.services.lambda.Lambda.findPsiElementsForHandler
import software.aws.toolkits.jetbrains.services.lambda.LambdaBuilder
import software.aws.toolkits.jetbrains.services.lambda.LambdaHandlerResolver
import software.aws.toolkits.jetbrains.services.lambda.RuntimeGroup
import software.aws.toolkits.jetbrains.services.lambda.execution.LambdaRunConfiguration
import software.aws.toolkits.jetbrains.services.lambda.execution.LambdaRunConfigurationBase
import software.aws.toolkits.jetbrains.services.lambda.execution.sam.SamTemplateUtils.findFunctionsFromTemplate
import software.aws.toolkits.jetbrains.services.lambda.runtimeGroup
import software.aws.toolkits.jetbrains.services.lambda.validOrNull
import software.aws.toolkits.jetbrains.settings.AwsSettingsConfigurable
import software.aws.toolkits.jetbrains.settings.SamSettings
import software.aws.toolkits.jetbrains.utils.ui.selected
import software.aws.toolkits.resources.message
import java.io.File
import javax.swing.JComponent

class SamRunConfigurationFactory(configuration: LambdaRunConfiguration) : ConfigurationFactory(configuration) {
    override fun createTemplateConfiguration(project: Project) = SamRunConfiguration(project, this)

    override fun getName(): String = "Local"

    override fun getOptionsClass() = LocalLambdaOptions::class.java
}

class SamRunConfiguration(project: Project, factory: ConfigurationFactory) :
    LambdaRunConfigurationBase<LocalLambdaOptions>(project, factory, "SAM CLI"),
    RefactoringListenerProvider {
    override fun getOptions() = super.getOptions() as LocalLambdaOptions

    override fun getConfigurationEditor() = SamRunSettingsEditor(project)

    override fun checkConfiguration() {
        if (SamSettings.getInstance().executablePath.isNullOrEmpty()) {
            throw RuntimeConfigurationError(message("sam.cli_not_configured")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AwsSettingsConfigurable::class.java)
            }
        }

        resolveCredentials()

        val (handler, runtime) = resolveLambdaInfo()
        handlerPsiElement(handler, runtime) ?: throw RuntimeConfigurationError(message("lambda.run_configuration.handler_not_found", handler))
        regionId() ?: throw RuntimeConfigurationError(message("lambda.run_configuration.no_region_specified"))
        resolveInput()
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): SamRunningState {
        try {
            if (SamSettings.getInstance().executablePath.isNullOrEmpty()) {
                throw RuntimeConfigurationError(message("sam.cli_not_configured")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, AwsSettingsConfigurable::class.java)
                }
            }

            val (handler, runtime, templateDetails) = resolveLambdaInfo()
            val psiElement = handlerPsiElement(handler, runtime)
                ?: throw RuntimeConfigurationError(message("lambda.run_configuration.handler_not_found", handler))

            val samRunSettings = SamRunSettings(
                runtime,
                handler,
                resolveInput(),
                environmentVariables(),
                resolveCredentials(),
                resolveRegion(),
                psiElement,
                templateDetails
            )

            return SamRunningState(environment, samRunSettings)
        } catch (e: Exception) {
            throw ExecutionException(e.message, e)
        }
    }

    override fun getRefactoringElementListener(element: PsiElement?): RefactoringElementListener? {
        element?.run {
            val handlerResolver = element.language.runtimeGroup?.let { runtimeGroup ->
                LambdaHandlerResolver.getInstance(runtimeGroup)
            } ?: return null

            val handlerPsi = handlerPsiElement() ?: return null

            if (PsiTreeUtil.isAncestor(element, handlerPsi, false)) {
                return object : RefactoringElementAdapter() {
                    private val originalHandler = options.functionOptions.handler

                    override fun elementRenamedOrMoved(newElement: PsiElement) {
                        handlerResolver.determineHandler(handlerPsi)?.let { newHandler ->
                            options.functionOptions.handler = newHandler
                        }
                    }

                    override fun undoElementMovedOrRenamed(newElement: PsiElement, oldQualifiedName: String) {
                        options.functionOptions.handler = originalHandler
                    }
                }
            }
        }
        return null
    }

    fun useTemplate(templateLocation: String?, logicalId: String?) {
        val functionOptions = options.functionOptions
        functionOptions.useTemplate = true

        functionOptions.templateFile = templateLocation
        functionOptions.logicalId = logicalId

        functionOptions.handler = null
        functionOptions.runtime = null
    }

    fun useHandler(runtime: Runtime?, handler: String?) {
        val functionOptions = options.functionOptions
        functionOptions.useTemplate = false

        functionOptions.templateFile = null
        functionOptions.logicalId = null

        functionOptions.handler = handler
        functionOptions.runtime = runtime.toString()
    }

    fun isUsingTemplate() = options.functionOptions.useTemplate

    fun templateFile() = options.functionOptions.templateFile

    fun logicalId() = options.functionOptions.logicalId

    fun handler() = options.functionOptions.handler

    fun runtime(): Runtime? = Runtime.fromValue(options.functionOptions.runtime)?.validOrNull

    fun environmentVariables() = options.functionOptions.environmentVariables

    fun environmentVariables(envVars: Map<String, String>) {
        options.functionOptions.environmentVariables = envVars.toMutableMap()
    }

    override fun suggestedName(): String? {
        val subName = options.functionOptions.logicalId ?: handlerDisplayName()
        return "[${message("lambda.run_configuration.local")}] $subName"
    }

    private fun handlerDisplayName(): String? {
        val handler = options.functionOptions.handler ?: return null
        return runtime()
            ?.runtimeGroup
            ?.let { LambdaHandlerResolver.getInstance(it) }
            ?.handlerDisplayName(handler) ?: handler
    }

    private fun resolveLambdaInfo() = if (isUsingTemplate()) {
            val template = templateFile()?.takeUnless { it.isEmpty() }
                ?: throw RuntimeConfigurationError(message("lambda.run_configuration.sam.no_template_specified"))

            val functionName = logicalId() ?: throw RuntimeConfigurationError(
                message("lambda.run_configuration.sam.no_function_specified")
            )

            val function = findFunctionsFromTemplate(
                project,
                File(template)
            ).find { it.logicalName == functionName }
                ?: throw RuntimeConfigurationError(
                    message(
                        "lambda.run_configuration.sam.no_such_function",
                        functionName,
                        template
                    )
                )

            val runtime = function.runtime().let { Runtime.fromValue(it).validOrNull }
                ?: throw RuntimeConfigurationError(message("lambda.run_configuration.no_runtime_specified"))

            Triple(function.handler(), runtime, SamTemplateDetails(template, functionName))
        } else {
            val handler = handler()
                ?: throw RuntimeConfigurationError(message("lambda.run_configuration.no_handler_specified"))
            val runtime = runtime()
                ?: throw RuntimeConfigurationError(message("lambda.run_configuration.no_runtime_specified"))

            Triple(handler, runtime, null)
        }

    private fun handlerPsiElement(handler: String? = handler(), runtime: Runtime? = runtime()) = try {
        runtime?.let {
            handler?.let {
                findPsiElementsForHandler(project, runtime, handler).firstOrNull()
            }
        }
    } catch (e: Exception) {
        null
    }
}

class SamRunSettingsEditor(project: Project) : SettingsEditor<SamRunConfiguration>() {
    private val view = SamRunSettingsEditorPanel(project, HandlerCompletionProvider(project))
    private val regionProvider = AwsRegionProvider.getInstance()
    private val credentialManager = CredentialManager.getInstance()

    init {
        val supported = LambdaBuilder.supportedRuntimeGroups
            .flatMap { it.runtimes }
            .sorted()

        val selected = RuntimeGroup.determineRuntime(project)?.let { if (it in supported) it else null }
        val accountSettingsManager = ProjectAccountSettingsManager.getInstance(project)

        view.setRuntimes(supported)
        view.runtime.selectedItem = selected

        view.regionSelector.setRegions(regionProvider.regions().values.toMutableList())
        view.regionSelector.selectedRegion = accountSettingsManager.activeRegion

        view.credentialSelector.setCredentialsProviders(credentialManager.getCredentialProviders())
        if (accountSettingsManager.hasActiveCredentials()) {
            view.credentialSelector.setSelectedCredentialsProvider(accountSettingsManager.activeCredentialProvider)
        }
    }

    override fun createEditor(): JComponent = view.panel

    override fun resetEditorFrom(configuration: SamRunConfiguration) {
        view.useTemplate.isSelected = configuration.isUsingTemplate()
        if (configuration.isUsingTemplate()) {
            view.runtime.isEnabled = false
            view.setTemplateFile(configuration.templateFile())
            view.selectFunction(configuration.logicalId())
        } else {
            view.setTemplateFile(null) // Also clears the functions selector
            view.runtime.model.selectedItem = configuration.runtime()
            view.handler.setText(configuration.handler())
        }

        view.environmentVariables.envVars = configuration.environmentVariables()
        view.regionSelector.selectedRegion = regionProvider.lookupRegionById(configuration.regionId())

        configuration.credentialProviderId()?.let {
            try {
                view.credentialSelector.setSelectedCredentialsProvider(credentialManager.getCredentialProvider(it))
            } catch (e: CredentialProviderNotFound) {
                // Use the raw string here to not munge what the customer had, will also allow it to show the error
                // that it could not be found
                view.credentialSelector.setSelectedInvalidCredentialsProvider(it)
            }
        }

        if (configuration.isUsingInputFile()) {
            view.lambdaInput.inputFile = configuration.inputSource()
        } else {
            view.lambdaInput.inputText = configuration.inputSource()
        }
    }

    override fun applyEditorTo(configuration: SamRunConfiguration) {
        if (view.useTemplate.isSelected) {
            configuration.useTemplate(view.templateFile.text, view.function.selected()?.logicalName)
        } else {
            configuration.useHandler(view.runtime.selected(), view.handler.text)
        }

        configuration.environmentVariables(view.environmentVariables.envVars)
        configuration.regionId(view.regionSelector.selectedRegion?.id)
        configuration.credentialProviderId(view.credentialSelector.getSelectedCredentialsProvider())
        if (view.lambdaInput.isUsingFile) {
            configuration.useInputFile(view.lambdaInput.inputFile)
        } else {
            configuration.useInputText(view.lambdaInput.inputText)
        }
    }
}

class SamRunSettings(
    val runtime: Runtime,
    val handler: String,
    val input: String,
    val environmentVariables: Map<String, String>,
    val credentials: ToolkitCredentialsProvider,
    val region: AwsRegion,
    val handlerElement: NavigatablePsiElement,
    val templateDetails: SamTemplateDetails?
) {
    val runtimeGroup: RuntimeGroup = runtime.runtimeGroup
        ?: throw IllegalStateException("Attempting to run SAM for unsupported runtime $runtime")
}

data class SamTemplateDetails(val templateFile: String, val logicalName: String)