package com.mundymorning.touchmusic

import android.content.Context
import com.mundymorning.touchmusic.data.AppDatabase
import com.mundymorning.touchmusic.data.LibraryRepository

class AppContainer(context: Context) {
    val db = AppDatabase.create(context)
    val repo = LibraryRepository(db)
}
