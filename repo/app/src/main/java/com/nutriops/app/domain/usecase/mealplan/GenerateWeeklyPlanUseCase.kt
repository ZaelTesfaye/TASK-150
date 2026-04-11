package com.nutriops.app.domain.usecase.mealplan

import com.nutriops.app.config.AppConfig
import com.nutriops.app.data.repository.MealPlanRepository
import com.nutriops.app.data.repository.ProfileRepository
import com.nutriops.app.domain.model.*
import com.nutriops.app.logging.AppLogger
import com.nutriops.app.security.RbacManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class GenerateWeeklyPlanUseCase @Inject constructor(
    private val mealPlanRepository: MealPlanRepository,
    private val profileRepository: ProfileRepository
) {
    data class MealSpec(
        val name: String,
        val description: String,
        val calories: Int,
        val proteinGrams: Double,
        val carbGrams: Double,
        val fatGrams: Double,
        val reasons: List<String>,
        val mealTime: MealTime
    )

    suspend fun execute(
        userId: String,
        weekStartDate: LocalDate,
        actorId: String,
        actorRole: Role
    ): Result<String> = withContext(Dispatchers.Default) {
        RbacManager.checkPermission(actorRole, RbacManager.Permission.CREATE_MEAL_PLAN)
            .getOrElse { return@withContext Result.failure(it) }
        RbacManager.checkObjectOwnership(actorId, userId, actorRole)
            .getOrElse { return@withContext Result.failure(it) }

        val profile = profileRepository.getProfileByUserId(userId)
            ?: return@withContext Result.failure(IllegalStateException("User profile not found. Create a profile first."))

        val weekEndDate = weekStartDate.plusDays(6)

        if (weekEndDate.isBefore(weekStartDate)) {
            return@withContext Result.failure(IllegalArgumentException("End date cannot be earlier than start date"))
        }

        val planResult = mealPlanRepository.createMealPlan(
            userId = userId,
            weekStartDate = weekStartDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            weekEndDate = weekEndDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            dailyCalorieBudget = profile.dailyCalorieBudget,
            proteinTarget = profile.proteinTargetGrams,
            carbTarget = profile.carbTargetGrams,
            fatTarget = profile.fatTargetGrams,
            actorId = actorId,
            actorRole = actorRole
        )

        val planId = planResult.getOrElse { return@withContext Result.failure(it) }

        val dietaryPattern = try { DietaryPattern.valueOf(profile.dietaryPattern) } catch (_: Exception) { DietaryPattern.STANDARD }
        val goal = try { HealthGoal.valueOf(profile.goal) } catch (_: Exception) { HealthGoal.MAINTAIN }

        // Generate meals for each day (1=Monday through 7=Sunday)
        for (day in 1..7) {
            val dailyMeals = generateDailyMeals(
                dailyCalories = profile.dailyCalorieBudget.toInt(),
                proteinTarget = profile.proteinTargetGrams.toInt(),
                carbTarget = profile.carbTargetGrams.toInt(),
                fatTarget = profile.fatTargetGrams.toInt(),
                dietaryPattern = dietaryPattern,
                goal = goal,
                dayOfWeek = day
            )

            for (meal in dailyMeals) {
                val reasonsJson = meal.reasons.joinToString(",", "[", "]") { "\"$it\"" }

                mealPlanRepository.addMeal(
                    mealPlanId = planId,
                    dayOfWeek = day.toLong(),
                    mealTime = meal.mealTime.name,
                    name = meal.name,
                    description = meal.description,
                    calories = meal.calories.toLong(),
                    proteinGrams = meal.proteinGrams,
                    carbGrams = meal.carbGrams,
                    fatGrams = meal.fatGrams,
                    reasons = reasonsJson
                )
            }

            // Generate swap options for each meal
            for (meal in dailyMeals) {
                generateSwapOptions(planId, meal, dietaryPattern, goal)
            }
        }

        AppLogger.info("MealPlan", "Weekly plan generated: $planId with ${7 * 3} meals")
        Result.success(planId)
    }

    /**
     * Generate 3 main meals per day (breakfast, lunch, dinner) with explainable reasons.
     * Each recommendation MUST show at least 2 explainable reasons.
     */
    private fun generateDailyMeals(
        dailyCalories: Int,
        proteinTarget: Int,
        carbTarget: Int,
        fatTarget: Int,
        dietaryPattern: DietaryPattern,
        goal: HealthGoal,
        dayOfWeek: Int
    ): List<MealSpec> {
        // Calorie distribution: breakfast 30%, lunch 40%, dinner 30%
        val breakfastCal = (dailyCalories * 0.30).toInt()
        val lunchCal = (dailyCalories * 0.40).toInt()
        val dinnerCal = dailyCalories - breakfastCal - lunchCal

        val breakfastProtein = (proteinTarget * 0.25).toDouble()
        val lunchProtein = (proteinTarget * 0.40).toDouble()
        val dinnerProtein = (proteinTarget - breakfastProtein - lunchProtein)

        val mealDb = getMealDatabase(dietaryPattern)
        val dayIndex = (dayOfWeek - 1) % mealDb.breakfasts.size

        val budgetReason = "Fits ${dailyCalories} kcal/day budget"
        val patternReason = "${dietaryPattern.name.lowercase().replaceFirstChar { it.uppercase() }} dietary pattern"
        val goalReason = goal.display

        return listOf(
            MealSpec(
                name = mealDb.breakfasts[dayIndex % mealDb.breakfasts.size].first,
                description = mealDb.breakfasts[dayIndex % mealDb.breakfasts.size].second,
                calories = breakfastCal,
                proteinGrams = breakfastProtein,
                carbGrams = (breakfastCal * 0.45 / 4),
                fatGrams = (breakfastCal * 0.30 / 9),
                reasons = listOf(budgetReason, patternReason, "Balanced breakfast for sustained energy"),
                mealTime = MealTime.BREAKFAST
            ),
            MealSpec(
                name = mealDb.lunches[dayIndex % mealDb.lunches.size].first,
                description = mealDb.lunches[dayIndex % mealDb.lunches.size].second,
                calories = lunchCal,
                proteinGrams = lunchProtein,
                carbGrams = (lunchCal * 0.40 / 4),
                fatGrams = (lunchCal * 0.30 / 9),
                reasons = listOf(budgetReason, "High protein for midday recovery", goalReason),
                mealTime = MealTime.LUNCH
            ),
            MealSpec(
                name = mealDb.dinners[dayIndex % mealDb.dinners.size].first,
                description = mealDb.dinners[dayIndex % mealDb.dinners.size].second,
                calories = dinnerCal,
                proteinGrams = dinnerProtein,
                carbGrams = (dinnerCal * 0.35 / 4),
                fatGrams = (dinnerCal * 0.35 / 9),
                reasons = listOf(budgetReason, patternReason, "Light dinner for better digestion"),
                mealTime = MealTime.DINNER
            )
        )
    }

    private suspend fun generateSwapOptions(
        planId: String,
        originalMeal: MealSpec,
        dietaryPattern: DietaryPattern,
        goal: HealthGoal
    ) {
        val swapDb = getMealDatabase(dietaryPattern)
        val candidates = when (originalMeal.mealTime) {
            MealTime.BREAKFAST -> swapDb.breakfasts
            MealTime.LUNCH -> swapDb.lunches
            MealTime.DINNER -> swapDb.dinners
            else -> swapDb.lunches
        }

        // Find the meal that was inserted for this original meal
        val meals = mealPlanRepository.getMealsByPlanId(planId)
        val originalDbMeal = meals.lastOrNull {
            it.name == originalMeal.name && it.mealTime == originalMeal.mealTime.name
        } ?: return

        for ((i, candidate) in candidates.withIndex()) {
            if (candidate.first == originalMeal.name) continue

            val swapCalories = (originalMeal.calories * (0.92 + i * 0.03)).toInt()
            val calDiffPercent = ((swapCalories - originalMeal.calories).toDouble() / originalMeal.calories * 100)
            val swapProtein = originalMeal.proteinGrams + (i - 1) * 2.0
            val proteinDiff = swapProtein - originalMeal.proteinGrams

            val withinTolerance = kotlin.math.abs(calDiffPercent) <= AppConfig.SWAP_CALORIE_TOLERANCE_PERCENT &&
                    kotlin.math.abs(proteinDiff) <= AppConfig.SWAP_PROTEIN_TOLERANCE_GRAMS

            // Deterministic rank: closer to original = higher score
            val rankScore = 100.0 - kotlin.math.abs(calDiffPercent) - kotlin.math.abs(proteinDiff)

            val reasons = listOf(
                "Within ${AppConfig.SWAP_CALORIE_TOLERANCE_PERCENT}% calorie tolerance",
                "${dietaryPattern.name.lowercase()} compatible"
            )

            mealPlanRepository.addSwapMapping(
                originalMealId = originalDbMeal.id,
                swapMealName = candidate.first,
                swapDescription = candidate.second,
                swapCalories = swapCalories.toLong(),
                swapProteinGrams = swapProtein,
                swapCarbGrams = originalMeal.carbGrams * (swapCalories.toDouble() / originalMeal.calories),
                swapFatGrams = originalMeal.fatGrams * (swapCalories.toDouble() / originalMeal.calories),
                caloriesDiffPercent = calDiffPercent,
                proteinDiffGrams = proteinDiff,
                isWithinTolerance = withinTolerance,
                swapReasons = reasons.joinToString(",", "[", "]") { "\"$it\"" },
                rankScore = rankScore
            )
        }
    }

    data class MealDatabase(
        val breakfasts: List<Pair<String, String>>,
        val lunches: List<Pair<String, String>>,
        val dinners: List<Pair<String, String>>
    )

    private fun getMealDatabase(pattern: DietaryPattern): MealDatabase = when (pattern) {
        DietaryPattern.VEGETARIAN, DietaryPattern.VEGAN -> MealDatabase(
            breakfasts = listOf(
                "Avocado Toast with Seeds" to "Whole grain toast topped with avocado, hemp seeds, and cherry tomatoes",
                "Smoothie Bowl" to "Mixed berry smoothie bowl with granola, chia seeds, and banana",
                "Oatmeal with Nuts" to "Steel-cut oats with walnuts, maple syrup, and fresh berries",
                "Tofu Scramble" to "Seasoned tofu scramble with peppers, spinach, and nutritional yeast",
                "Banana Pancakes" to "Whole wheat pancakes with banana, blueberries, and agave",
                "Chia Pudding" to "Coconut milk chia pudding with mango and passion fruit",
                "Granola Parfait" to "Layered coconut yogurt with homemade granola and fruits"
            ),
            lunches = listOf(
                "Quinoa Buddha Bowl" to "Quinoa with roasted vegetables, chickpeas, and tahini dressing",
                "Lentil Soup" to "Red lentil soup with cumin, turmeric, and crusty bread",
                "Veggie Wrap" to "Whole wheat wrap with hummus, grilled vegetables, and mixed greens",
                "Mediterranean Salad" to "Mixed greens with falafel, olives, tomatoes, and lemon dressing",
                "Black Bean Tacos" to "Corn tortillas with seasoned black beans, salsa, and avocado",
                "Pesto Pasta" to "Whole grain pasta with basil pesto, cherry tomatoes, and pine nuts",
                "Stuffed Bell Peppers" to "Bell peppers stuffed with rice, beans, corn, and spices"
            ),
            dinners = listOf(
                "Stir-Fry Tofu" to "Tofu stir-fry with broccoli, snap peas, and ginger sauce over brown rice",
                "Vegetable Curry" to "Coconut curry with chickpeas, sweet potato, and spinach with rice",
                "Mushroom Risotto" to "Creamy arborio rice with mixed mushrooms, peas, and herbs",
                "Eggplant Parmesan" to "Baked eggplant with marinara sauce and plant-based cheese",
                "Thai Peanut Noodles" to "Rice noodles with peanut sauce, edamame, and vegetables",
                "Cauliflower Steak" to "Roasted cauliflower steak with chimichurri and roasted potatoes",
                "Bean Chili" to "Three-bean chili with cornbread and mixed green salad"
            )
        )
        DietaryPattern.KETO -> MealDatabase(
            breakfasts = listOf(
                "Eggs Benedict" to "Poached eggs with hollandaise on portobello mushroom caps",
                "Bacon & Cheese Omelet" to "Three-egg omelet with cheddar, bacon, and chives",
                "Keto Smoothie" to "Avocado, coconut milk, and cocoa powder smoothie",
                "Sausage Egg Cups" to "Baked egg cups with Italian sausage and cheese",
                "Almond Flour Waffles" to "Low-carb waffles with butter and sugar-free syrup",
                "Cream Cheese Pancakes" to "Two-ingredient pancakes with cream cheese and eggs",
                "Smoked Salmon Plate" to "Smoked salmon with cream cheese, capers, and cucumber"
            ),
            lunches = listOf(
                "Caesar Salad" to "Romaine with grilled chicken, parmesan, and keto Caesar dressing",
                "Tuna Avocado Bowl" to "Tuna salad in avocado halves with olive oil and herbs",
                "Lettuce Wraps" to "Turkey and cheese lettuce wraps with mayo and pickles",
                "Cobb Salad" to "Mixed greens with chicken, bacon, egg, avocado, and blue cheese",
                "Zucchini Boats" to "Zucchini filled with ground beef, cheese, and Italian herbs",
                "Chicken Soup" to "Creamy chicken soup with celery, herbs, and heavy cream",
                "Shrimp Plate" to "Grilled shrimp with garlic butter and steamed asparagus"
            ),
            dinners = listOf(
                "Grilled Steak" to "Ribeye steak with garlic butter, asparagus, and cauliflower mash",
                "Salmon Fillet" to "Pan-seared salmon with lemon dill sauce and roasted broccoli",
                "Chicken Thighs" to "Crispy chicken thighs with creamed spinach and mushrooms",
                "Pork Chops" to "Pan-fried pork chops with green beans almondine and butter",
                "Lamb Chops" to "Herb-crusted lamb chops with mint pesto and roasted vegetables",
                "Cod en Papillote" to "Baked cod in parchment with tomatoes, olives, and capers",
                "Beef Stir-Fry" to "Beef strips with bell peppers, broccoli in sesame oil"
            )
        )
        else -> MealDatabase(
            breakfasts = listOf(
                "Greek Yogurt Parfait" to "Greek yogurt with honey, granola, and mixed berries",
                "Veggie Omelet" to "Three-egg omelet with spinach, mushrooms, and feta cheese",
                "Overnight Oats" to "Rolled oats soaked in almond milk with chia seeds and banana",
                "Whole Grain Toast" to "Whole grain toast with almond butter, banana slices, and honey",
                "Protein Smoothie" to "Whey protein with banana, spinach, peanut butter, and almond milk",
                "Egg & Avocado Bowl" to "Scrambled eggs with avocado, tomato, and whole grain toast",
                "Cottage Cheese Bowl" to "Cottage cheese with pineapple, walnuts, and a drizzle of honey"
            ),
            lunches = listOf(
                "Grilled Chicken Salad" to "Mixed greens with grilled chicken breast, quinoa, and vinaigrette",
                "Turkey Club Wrap" to "Whole wheat wrap with turkey, avocado, lettuce, and tomato",
                "Salmon Poke Bowl" to "Brown rice with salmon cubes, edamame, avocado, and soy dressing",
                "Chicken & Rice Bowl" to "Seasoned chicken breast over brown rice with steamed broccoli",
                "Mediterranean Bowl" to "Grilled chicken with hummus, tabbouleh, and pita bread",
                "Asian Noodle Bowl" to "Rice noodles with shrimp, vegetables, and ginger-soy sauce",
                "Turkey Meatball Sub" to "Whole wheat roll with turkey meatballs, marinara, and mozzarella"
            ),
            dinners = listOf(
                "Baked Salmon" to "Oven-baked salmon with roasted sweet potato and steamed green beans",
                "Chicken Stir-Fry" to "Chicken breast stir-fry with mixed vegetables over brown rice",
                "Lean Beef Tacos" to "Corn tortillas with lean ground beef, salsa, and Greek yogurt",
                "Turkey Bolognese" to "Whole wheat spaghetti with turkey meat sauce and side salad",
                "Grilled Shrimp" to "Garlic-herb grilled shrimp with quinoa and roasted asparagus",
                "Chicken Breast" to "Herb-roasted chicken breast with mashed sweet potato and greens",
                "Fish & Vegetables" to "Pan-seared white fish with roasted Mediterranean vegetables"
            )
        )
    }
}
