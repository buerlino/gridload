package io.github.buerlino.gridload.core

/**
 * Common appliances offered when adding one, as starting values the user can change.
 *
 * [Appliance.watts] is the average draw during the part of a use that draws it, and
 * [Appliance.runMinutes] how long that part lasts, so that watts × time is about one use's real
 * energy. That keeps the quarter-hour average (the peak) right. A washing machine heats at ~2 kW for ~30 minutes and
 * then barely draws anything, so 2000 W over its whole 2-hour programme would count 4 kWh for a
 * ~1 kWh wash. Appliances that cycle (oven) use their average over the heavy part.
 *
 * Heating and hot water (heat pump, boiler) are not here: they are in the baseline from the
 * imported hourly days, and adding them would count them twice.
 */
val APPLIANCE_PRESETS: List<Appliance> = listOf(
    // Heating phase at 40 to 60 °C; ~1 kWh per wash.
    preset("Washing machine", 2000, 30, interruptible = false),
    // Heat pump dryer, the common kind today; ~1.6 kWh per load.
    preset("Tumble dryer", 800, 120, interruptible = false),
    // Two heating phases, counted as one; ~1 kWh per run.
    preset("Dishwasher", 2000, 30, interruptible = false),
    // Preheating at up to 3 kW, then the thermostat cycles; ~0.8 kWh.
    preset("Oven", 2500, 20, interruptible = false),
    // Induction, a meal on two zones; ~1 kWh.
    preset("Cooktop", 2000, 30, interruptible = false),
    // About a litre.
    preset("Kettle", 2200, 3, interruptible = false),
    preset("Coffee machine", 1300, 3, interruptible = false),
    preset("Microwave", 1200, 5, interruptible = false),
    // EU limit is 900 W.
    preset("Vacuum cleaner", 800, 20),
    preset("Hair dryer", 1800, 10, interruptible = false),
    // Portable fan heater, runs until switched off.
    preset("Space heater", 2000, null),
    // 11 kW wallbox (three phases); 3.7 kW on one phase. Runs until stopped.
    preset("Car charger", 11000, null),
)

private fun preset(name: String, watts: Int, runMinutes: Int?, interruptible: Boolean = true) =
    Appliance(id = "", name = name, watts = watts, runMinutes = runMinutes, interruptible = interruptible)
