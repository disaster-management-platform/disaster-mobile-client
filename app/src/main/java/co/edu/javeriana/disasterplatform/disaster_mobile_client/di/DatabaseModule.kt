package co.edu.javeriana.disasterplatform.disaster_mobile_client.di

import android.content.Context
import androidx.room.Room
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.local.DisasterDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): DisasterDatabase =
        Room.databaseBuilder(context, DisasterDatabase::class.java, "disasters.db").build()

    @Provides
    fun provideDisasterDao(database: DisasterDatabase) = database.disasterDao()
}
