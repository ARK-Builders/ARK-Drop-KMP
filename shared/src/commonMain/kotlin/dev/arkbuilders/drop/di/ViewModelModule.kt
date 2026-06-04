package dev.arkbuilders.drop.di

import dev.arkbuilders.drop.instrumentation.AnalyticsReporter
import dev.arkbuilders.drop.instrumentation.FirebaseReporter
import dev.arkbuilders.drop.presentation.edit.EditProfileViewModel
import dev.arkbuilders.drop.presentation.history.HistoryViewModel
import dev.arkbuilders.drop.presentation.home.HomeViewModel
import dev.arkbuilders.drop.presentation.receive.ReceiveViewModel
import dev.arkbuilders.drop.presentation.send.SendViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val viewModelModule =
    module {
        viewModel { HistoryViewModel(get(), get<AnalyticsReporter>()) }
        viewModel { HomeViewModel(get(), get(), get()) }
        viewModel { EditProfileViewModel(get(), get(), get<AnalyticsReporter>()) }
        viewModel {
            ReceiveViewModel(
                get(),
                get(),
                get<FirebaseReporter>(),
                get<AnalyticsReporter>(),
            )
        }
        viewModel {
            SendViewModel(
                get(),
                get(),
                get(),
                get<FirebaseReporter>(),
                get<AnalyticsReporter>(),
            )
        }
    }
