package dev.arkbuilders.drop.di

import dev.arkbuilders.drop.data.helper.AvatarHelper
import dev.arkbuilders.drop.data.helper.NetworkStatus
import dev.arkbuilders.drop.data.helper.PermissionsHelper
import dev.arkbuilders.drop.data.helper.ResourcesHelper
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single { AvatarHelper(androidContext()) }
    single { NetworkStatus(androidContext()) }
    single { PermissionsHelper(androidContext()) }
    single { ResourcesHelper(androidContext()) }
}