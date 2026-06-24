package dev.brahmkshatriya.echo.di

import dev.brahmkshatriya.echo.download.DownloadWorker
import dev.brahmkshatriya.echo.download.Downloader
import dev.brahmkshatriya.echo.download.db.DownloadDatabase
import dev.brahmkshatriya.echo.extensions.ExtensionLoader
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.history.db.HistoryDatabase
import androidx.media3.common.MediaItem
import dev.brahmkshatriya.echo.playback.PlayerService
import dev.brahmkshatriya.echo.playback.PlayerState
import kotlinx.coroutines.flow.MutableStateFlow
import dev.brahmkshatriya.echo.ui.common.SnackBarHandler
import dev.brahmkshatriya.echo.ui.common.UiViewModel
import dev.brahmkshatriya.echo.ui.download.DownloadViewModel
import dev.brahmkshatriya.echo.ui.history.HistoryViewModel
import dev.brahmkshatriya.echo.ui.extensions.ExtensionInfoViewModel
import dev.brahmkshatriya.echo.ui.extensions.ExtensionsViewModel
import dev.brahmkshatriya.echo.ui.extensions.add.AddViewModel
import dev.brahmkshatriya.echo.ui.extensions.login.LoginUserListViewModel
import dev.brahmkshatriya.echo.ui.extensions.login.LoginViewModel
import dev.brahmkshatriya.echo.ui.feed.FeedViewModel
import dev.brahmkshatriya.echo.ui.main.search.SearchViewModel
import dev.brahmkshatriya.echo.ui.media.MediaViewModel
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.ui.player.more.info.TrackInfoViewModel
import dev.brahmkshatriya.echo.ui.player.more.lyrics.LyricsViewModel
import dev.brahmkshatriya.echo.ui.playlist.create.CreatePlaylistViewModel
import dev.brahmkshatriya.echo.ui.playlist.delete.DeletePlaylistViewModel
import dev.brahmkshatriya.echo.ui.playlist.edit.EditPlaylistViewModel
import dev.brahmkshatriya.echo.ui.playlist.save.SaveToPlaylistViewModel
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.HealthMonitor
import org.koin.android.ext.koin.androidApplication
import org.koin.androidx.workmanager.dsl.workerOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

object DI {

    private val baseModule = module {
        single { androidApplication().getSettings() }
        single { HealthMonitor(androidApplication()) }
        singleOf(::App)
    }

    private val extensionModule = module {
        includes(baseModule)
        singleOf(::ExtensionLoader)
    }

    private val downloadModule = module {
        includes(extensionModule)
        singleOf(DownloadDatabase::create)
        singleOf(::Downloader)
        workerOf(::DownloadWorker)
    }

    private val historyModule = module {
        singleOf(HistoryDatabase::create)
        single { get<HistoryDatabase>().historyDao() }
        singleOf(::HistoryRepository)
    }

    private val playerModule = module {
        includes(extensionModule)
        singleOf(PlayerService::getCache)
        single { PlayerState() }
        single { MutableStateFlow<List<MediaItem>>(emptyList()) }
    }

    private val uiModules = module {
        singleOf(::SnackBarHandler)
        viewModelOf(::UiViewModel)

        viewModelOf(::PlayerViewModel)
        viewModelOf(::LyricsViewModel)
        viewModelOf(::TrackInfoViewModel)

        viewModelOf(::ExtensionsViewModel)
        viewModelOf(::ExtensionInfoViewModel)
        viewModelOf(::LoginUserListViewModel)
        viewModelOf(::AddViewModel)
        viewModelOf(::LoginViewModel)

        viewModelOf(::FeedViewModel)
        viewModelOf(::SearchViewModel)
        viewModelOf(::MediaViewModel)

        viewModelOf(::CreatePlaylistViewModel)
        viewModelOf(::DeletePlaylistViewModel)
        viewModelOf(::SaveToPlaylistViewModel)
        viewModelOf(::EditPlaylistViewModel)

        viewModelOf(::DownloadViewModel)
        viewModelOf(::HistoryViewModel)
    }

    val appModule = module {
        includes(baseModule)
        includes(extensionModule)
        includes(playerModule)
        includes(downloadModule)
        includes(historyModule)
        includes(uiModules)
    }
}