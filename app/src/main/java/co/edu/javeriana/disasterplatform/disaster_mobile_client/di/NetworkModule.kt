package co.edu.javeriana.disasterplatform.disaster_mobile_client.di

import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote.DisasterApi
import co.edu.javeriana.disasterplatform.disaster_mobile_client.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.converter.scalars.ScalarsConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Singleton
    @Provides
    fun providesRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
    }

    @Singleton
    @Provides
    fun providesDisasterRetrofitService(retrofit: Retrofit): DisasterApi {
        return retrofit.create(DisasterApi::class.java)
    }
}
