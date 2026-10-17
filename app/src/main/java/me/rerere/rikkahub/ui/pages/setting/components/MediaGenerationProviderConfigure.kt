package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationManager
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

val MediaGenerationProviderSetting.typeName: String
    @Composable get() = when (this) {
        is MediaGenerationProviderSetting.OpenAI -> "OpenAI"
        is MediaGenerationProviderSetting.Aliyun -> stringResource(R.string.media_provider_type_aliyun)
        is MediaGenerationProviderSetting.Volcengine -> stringResource(R.string.media_provider_type_volcengine)
        is MediaGenerationProviderSetting.MiniMax -> "MiniMax"
        is MediaGenerationProviderSetting.OpenRouter -> "OpenRouter"
    }

val MediaKind.label: String
    @Composable get() = when (this) {
        MediaKind.IMAGE -> stringResource(R.string.media_kind_image)
        MediaKind.VIDEO -> stringResource(R.string.video)
    }

@Composable
fun MediaGenerationProviderConfigure(
    setting: MediaGenerationProviderSetting,
    modifier: Modifier = Modifier,
    onValueChange: (MediaGenerationProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.verticalScroll(rememberScrollState())
    ) {
        FormItem(label = { Text(stringResource(R.string.setting_media_page_provider_type)) }) {
            OutlinedTextField(
                value = setting.typeName,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        FormItem(label = { Text(stringResource(R.string.setting_media_page_provider_name)) }) {
            OutlinedTextField(
                value = setting.name,
                onValueChange = { onValueChange(setting.copyProvider(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        FormItem(label = { Text(stringResource(R.string.setting_media_page_api_key)) }) {
            OutlinedTextField(
                value = setting.apiKey,
                onValueChange = { onValueChange(setting.copyProvider(apiKey = it.trim())) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.setting_media_page_api_key_hint)) }
            )
        }

        FormItem(label = { Text(stringResource(R.string.setting_media_page_base_url)) }) {
            OutlinedTextField(
                value = setting.baseUrl,
                onValueChange = { onValueChange(setting.copyProvider(baseUrl = it.trim())) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.setting_media_page_base_url_hint)) }
            )
        }

        if (setting is MediaGenerationProviderSetting.Aliyun) {
            FormItem(
                label = { Text(stringResource(R.string.setting_media_page_workspace_id)) },
                description = {
                    Text(
                        stringResource(
                            R.string.setting_media_page_workspace_id_desc,
                            MediaGenerationProviderSetting.Aliyun.WORKSPACE_PLACEHOLDER,
                        )
                    )
                }
            ) {
                OutlinedTextField(
                    value = setting.workspaceId,
                    onValueChange = { onValueChange(setting.copy(workspaceId = it.trim())) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        FormItem(
            label = { Text(stringResource(R.string.setting_media_page_models)) },
            description = { Text(stringResource(R.string.setting_media_page_models_desc)) }
        ) {
            FetchModelsButton(
                setting = setting,
                onModelsFetched = { ids ->
                    // 保留现有已填的模型，把新拉到的追加进去；已有的行不受影响
                    val existing = setting.models.mapNotNull { it.modelId.trim().takeIf(String::isNotBlank) }.toSet()
                    val merged = setting.models.toMutableList()
                    ids.filter { it !in existing }.forEach { id ->
                        merged += MediaGenerationModel(
                            modelId = id,
                            kind = if (id.contains("video") || id.contains("veo") || id.contains("seedance") || id.contains("wan") && !id.contains("image")) MediaKind.VIDEO else MediaKind.IMAGE,
                        )
                    }
                    onValueChange(setting.copyProvider(models = merged))
                }
            )
            MediaGenerationModelList(
                models = setting.models,
                supportedKinds = setting.supportedKinds,
                onValueChange = { onValueChange(setting.copyProvider(models = it)) }
            )
        }
    }
}

@Composable
private fun FetchModelsButton(
    setting: MediaGenerationProviderSetting,
    onModelsFetched: (List<String>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val manager: MediaGenerationManager = koinInject()
    val toaster = LocalToaster.current
    var fetching by remember { mutableStateOf(false) }
    val textNoKey = stringResource(R.string.setting_media_page_fetch_models_no_key)
    val textEmpty = stringResource(R.string.setting_media_page_fetch_models_empty)
    val textDone = stringResource(R.string.setting_media_page_fetch_models_done)
    val textFailed = stringResource(R.string.setting_media_page_fetch_models_failed)

    OutlinedButton(
        onClick = {
            if (setting.apiKey.isBlank()) {
                toaster.show(textNoKey, type = ToastType.Warning)
                return@OutlinedButton
            }
            scope.launch {
                fetching = true
                try {
                    manager.fetchModels(setting)
                        .onSuccess { ids ->
                            if (ids.isEmpty()) {
                                toaster.show(textEmpty, type = ToastType.Warning)
                            } else {
                                onModelsFetched(ids)
                                toaster.show(textDone.format(ids.size), type = ToastType.Success)
                            }
                        }
                        .onFailure { e ->
                            toaster.show(textFailed.format(e.message.orEmpty()), type = ToastType.Error)
                        }
                } finally {
                    fetching = false
                }
            }
        },
        enabled = !fetching && setting.baseUrl.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (fetching) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
        } else {
            Icon(HugeIcons.Refresh03, null, modifier = Modifier.size(18.dp))
        }
        Text(
            stringResource(R.string.setting_media_page_fetch_models),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun MediaGenerationModelList(
    models: List<MediaGenerationModel>,
    supportedKinds: Set<MediaKind>,
    onValueChange: (List<MediaGenerationModel>) -> Unit
) {
    // 按枚举顺序排列，保证分段按钮的顺序稳定
    val kinds = MediaKind.entries.filter { it in supportedKinds }

    fun update(model: MediaGenerationModel) {
        onValueChange(models.map { if (it.id == model.id) model else it })
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        models.forEach { model ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedTextField(
                        value = model.modelId,
                        onValueChange = { update(model.copy(modelId = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.setting_media_page_model_id)) },
                        singleLine = true
                    )
                    // 只支持一种类型的厂商不需要选择
                    if (kinds.size > 1) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            kinds.forEachIndexed { index, kind ->
                                SegmentedButton(
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = kinds.size),
                                    selected = model.kind == kind,
                                    onClick = { update(model.copy(kind = kind)) },
                                ) {
                                    Text(kind.label)
                                }
                            }
                        }
                    }
                }
                IconButton(onClick = { onValueChange(models.filter { it.id != model.id }) }) {
                    Icon(HugeIcons.Delete01, stringResource(R.string.delete))
                }
            }
        }

        TextButton(
            onClick = { onValueChange(models + MediaGenerationModel(modelId = "", kind = kinds.first())) },
            enabled = kinds.isNotEmpty()
        ) {
            Icon(HugeIcons.Add01, null)
            Text(stringResource(R.string.setting_media_page_add_model))
        }
    }
}
