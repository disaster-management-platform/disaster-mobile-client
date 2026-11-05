package co.edu.javeriana.disasterplatform.disaster_mobile_client.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.screens.DisastersScreen
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.screens.FormScreen
import co.edu.javeriana.disasterplatform.disaster_mobile_client.ui.screens.HomeScreen

@Composable
fun AppNavigation(
    navController: NavHostController
){
    NavHost(
        navController = navController,
        startDestination = "home"
    ){
        composable(route = "home"){
            HomeScreen(navController)
        }

        composable(route = "form") {
            FormScreen(navController)
        }

        composable(route = "disasters") {
            DisastersScreen(navController)
        }
    }
}