package co.edu.javeriana.disasterplatform.disaster_mobile_client.di

import androidx.viewbinding.BuildConfig
import co.edu.javeriana.disasterplatform.disaster_mobile_client.data.remote.DisasterApi
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
            // Localhost si el backend esta corriendo en el mismo dispositivo
            .baseUrl("http://localhost:5080/api/")
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
