package com.abdownloadmanager.desktop.pages.addDownload.multiple

import com.abdownloadmanager.desktop.pages.addDownload.AddDownloadComponent
import com.abdownloadmanager.desktop.repository.AppRepository
import com.abdownloadmanager.shared.ui.widget.customtable.TableState
import com.abdownloadmanager.shared.utils.DownloadSystem
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import arrow.core.Some
import com.abdownloadmanager.desktop.pages.addDownload.AddDownloadCredentialsInUiProps
import com.abdownloadmanager.shared.downloaderinui.DownloaderInUiRegistry
import com.abdownloadmanager.shared.downloaderinui.add.TANewDownloadInputs
import com.abdownloadmanager.shared.ui.configurable.Configurable
import com.abdownloadmanager.shared.utils.FileIconProvider
import com.abdownloadmanager.shared.utils.category.Category
import com.abdownloadmanager.shared.utils.category.CategoryItem
import com.abdownloadmanager.shared.utils.category.CategoryManager
import com.abdownloadmanager.shared.utils.category.CategorySelectionMode
import com.abdownloadmanager.shared.utils.perhostsettings.PerHostSettingsManager
import com.abdownloadmanager.shared.utils.perhostsettings.getSettingsForURL
import com.arkivanov.decompose.ComponentContext
import ir.amirab.downloader.NewDownloadItemProps
import ir.amirab.downloader.downloaditem.EmptyContext
import ir.amirab.downloader.queue.QueueManager
import ir.amirab.downloader.utils.OnDuplicateStrategy
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AddMultiDownloadComponent(
    ctx: ComponentContext,
    id: String,
    private val onRequestClose: () -> Unit,
    private val onRequestAdd: OnRequestAdd,
    private val onRequestAddCategory: () -> Unit,
) : AddDownloadComponent(ctx, id),
    KoinComponent {
    override val shouldShowWindow: StateFlow<Boolean> = MutableStateFlow(true)
    val tableState = TableState(
        cells = AddMultiItemTableCells.all(),
        forceVisibleCells = listOf(
            AddMultiItemTableCells.Check,
            AddMultiItemTableCells.Name,
        )
    )
    private val appSettings by inject<AppRepository>()
    private val perHostSettingsManager by inject<PerHostSettingsManager>()
    val downloadSystem by inject<DownloadSystem>()
    val fileIconProvider: FileIconProvider by inject()


    private val _folder = MutableStateFlow(appSettings.saveLocation.value)
    val folder = _folder.asStateFlow()
    fun setFolder(folder: String) {
        this._folder.update { folder }
        list.forEach {
            it.folder.update { folder }
        }
    }

    // when we select all files in one location let user option to auto categorize items
    private val _alsoAutoCategorize = MutableStateFlow(true)
    val alsoAutoCategorize = _alsoAutoCategorize.asStateFlow()
    fun setAlsoAutoCategorize(value: Boolean) {
        _alsoAutoCategorize.update { value }
    }


    private val categoryManager: CategoryManager by inject()
    val categories = categoryManager.categoriesFlow
    private val _selectedCategory = MutableStateFlow<Category?>(null)
    val selectedCategory = _selectedCategory.asStateFlow()

    fun setSelectedCategory(category: Category?) {
        _selectedCategory.update {
            category
        }
    }

    private val _allInSameLocation = MutableStateFlow(false)
    val allInSameLocation = _allInSameLocation.asStateFlow()

    fun setAllItemsInSameLocation(sameLocation: Boolean) {
        _allInSameLocation.update { sameLocation }
    }

    fun requestAddCategory() {
        onRequestAddCategory()
    }

    val downloaderInUiRegistry: DownloaderInUiRegistry by inject()

    private fun newCheckerWithInputs(
        addDownloadCredentialsInUiProps: AddDownloadCredentialsInUiProps
    ): TANewDownloadInputs? {
        val iDownloadCredentials = addDownloadCredentialsInUiProps.credentials
        return downloaderInUiRegistry
            .getDownloaderOf(iDownloadCredentials)
            ?.createNewDownloadInputs(
                initialCredentials = iDownloadCredentials,
                initialName = addDownloadCredentialsInUiProps.extraConfig.getAndFixSuggestedName().orEmpty(),
                initialFolder = folder.value,
                downloadSystem = downloadSystem,
                scope = scope,
            )
    }

    fun addItems(list: List<AddDownloadCredentialsInUiProps>) {
        val newItemsToAdd = list.filter {
            it.credentials !in this.list.map {
                it.credentials.value
            }
        }.mapNotNull {
            newCheckerWithInputs(it)
                ?.also { inputComponent ->
                    val perHostSettingsItem = perHostSettingsManager
                        .getSettingsForURL(it.credentials.link)
                    perHostSettingsItem?.let {
                        inputComponent
                            .applyHostSettingsToExtraConfig(perHostSettingsItem)
                    }
                }
        }
        enqueueCheck(newItemsToAdd)
        this.list = this.list.plus(newItemsToAdd)
    }

    var list: List<TANewDownloadInputs> by mutableStateOf(emptyList())

    private val checkList = MutableSharedFlow<TANewDownloadInputs>()
    private fun enqueueCheck(links: List<TANewDownloadInputs>) {
        scope.launch {
            for (i in links) {
                checkList.emit(i)
            }
        }
    }

    init {
        checkList.onEach {
            it.downloadUiChecker.refresh()
        }
            .launchIn(scope)
    }

    var selectionList by mutableStateOf<List<String>>(emptyList())
    fun isSelected(item: TANewDownloadInputs): Boolean {
        return item.credentials.value.link in selectionList
    }

    val isAllSelected by derivedStateOf {
        list.all { it.credentials.value.link in selectionList }
    }

    var lastSelectedId by mutableStateOf(null as String?)

    fun setSelect(id: String, selected: Boolean) {
        if (selected) {
            lastSelectedId = id
            if (!selectionList.contains(id)) {
                selectionList = selectionList.plus(id)
            }
        } else {
            selectionList = selectionList.minus(id)
        }
    }

    fun resetSelectionTo(ids: List<String>, boolean: Boolean) {
        selectionList = ids.takeIf { boolean }
            .orEmpty()
    }

    fun selectAll(value: Boolean) {
        selectionList = if (value) {
            list.map { it.credentials.value.link }
        } else {
            emptyList()
        }
    }

    val canClickAdd by derivedStateOf {
        selectionList.isNotEmpty()
    }
    private val queueManager: QueueManager by inject()
    val queueList = queueManager.queues

    private fun getFolderForItem(
        categorySelectionMode: CategorySelectionMode?,
        allInSameLocation: Boolean,
        url: String,
        fleName: String,
        defaultFolder: String,
    ): String {
        if (allInSameLocation) return defaultFolder
        return when (categorySelectionMode) {
            CategorySelectionMode.Auto -> {
                downloadSystem.categoryManager
                    .getCategoryOf(
                        CategoryItem(
                            url = url,
                            fileName = fleName,
                        )
                    )?.getDownloadPath()
                    ?: defaultFolder
            }

            is CategorySelectionMode.Fixed -> {
                downloadSystem.categoryManager
                    .getCategoryById(categorySelectionMode.categoryId)?.getDownloadPath()
                    ?: defaultFolder
            }

            null -> defaultFolder
        }
    }

    fun requestAddDownloads(
        queueId: Long?, startQueue: Boolean,
    ) {

        val categorySelectionMode = when {
            alsoAutoCategorize.value -> CategorySelectionMode.Auto
            else -> selectedCategory.value?.let {
                CategorySelectionMode.Fixed(it.id)
            }
        }
        val itemsToAdd = list
            .filter { it.credentials.value.link in selectionList }
            .filter {
                val checker = it.downloadUiChecker
                checker.canAdd.value
                        || checker.isDuplicate.value // we add numbered file strategy
            }
            .map {
                NewDownloadItemProps(
                    downloadItem = it.downloadItem.value.copy(
                        folder = Some(
                            getFolderForItem(
                                categorySelectionMode = categorySelectionMode,
                                url = it.credentials.value.link,
                                fleName = it.name.value,
                                defaultFolder = it.folder.value,
                                allInSameLocation = allInSameLocation.value
                            )
                        )
                    ),
                    extraConfig = it.downloadJobConfig.value,
                    onDuplicateStrategy = OnDuplicateStrategy.AddNumbered,
                    context = EmptyContext,
                )
            }
        consumeDialog {
            onRequestAdd(
                items = itemsToAdd,
                queueId = queueId,
                categorySelectionMode = categorySelectionMode
            ).invokeOnCompletion {
                val folder = folder.value
                if (allInSameLocation.value) {
                    addToLastUsedLocations(folder)
                }
                if (startQueue && queueId != null) {
                    scope.launch {
                        downloadSystem.startQueue(queueId)
                    }
                }
            }
            requestClose()
        }
    }

    var showAddToQueue by mutableStateOf(false)
        private set

    fun getIdOf(item: TANewDownloadInputs): Int {
        return item.getUniqueId()
    }

    fun openConfigurableList(
        itemID: Int?
    ) {
        currentDownloadConfigurableList.value = itemID?.let { id ->
            list.find { getIdOf(it) == id }
        }?.configurableList
    }

    val currentDownloadConfigurableList: MutableStateFlow<List<Configurable<*>>?> = MutableStateFlow(null)

    fun openAddToQueueDialog() {
        showAddToQueue = true
    }

    fun closeAddToQueue() {
        showAddToQueue = false
    }

    fun requestClose() {
        onRequestClose()
    }
}

fun interface OnRequestAdd {
    operator fun invoke(
        items: List<NewDownloadItemProps>,
        queueId: Long?,
        categorySelectionMode: CategorySelectionMode?,
    ): Deferred<List<Long>>
}
