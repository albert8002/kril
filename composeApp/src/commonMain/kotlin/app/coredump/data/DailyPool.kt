package app.coredump.data

/**
 * The lists the daily run is drawn from, fixed for good: the hand-made packs as of build 58. New packs and lists added
 * later never change which prompts a day gets, so every player (on any build since) plays the same daily.
 */
object DailyPool {
    val IDS: Set<String> = setOf(
        "art.rhyme", "code.ext", "code.key", "code.lang", "cul.band", "cul.boardgame", "cul.cartoon", "cul.color",
        "cul.composer", "cul.dance", "cul.egypt", "cul.fairytale", "cul.fictcat", "cul.genre", "cul.greekgod",
        "cul.instrument", "cul.language", "cul.musical", "cul.norse", "cul.novel", "cul.painter", "cul.romangod",
        "cul.singer", "cul.superhero", "cul.vgchar", "food.alcohol", "food.berry", "food.breakfast", "food.cake",
        "food.candy", "food.cheese", "food.chinese", "food.cocktail", "food.coffee", "food.condiment", "food.cooking",
        "food.dessert", "food.drink", "food.fastfood", "food.french", "food.fruit", "food.grain", "food.icecream",
        "food.indian", "food.italian", "food.mexican", "food.nut", "food.pasta", "food.pizza", "food.sandwich",
        "food.snack", "food.soup", "food.spice", "food.utensil", "food.veg", "geo.africa", "geo.asia", "geo.capital",
        "geo.country", "geo.desert", "geo.europe", "geo.europecity", "geo.island", "geo.landmark", "geo.mountain",
        "geo.park", "geo.river", "geo.uscap", "geo.uscity", "geo.usstate", "geo.waterfall", "gk.africap", "gk.amcap",
        "gk.asiacap", "gk.battle", "gk.bible", "gk.cardgame", "gk.collective", "gk.eu", "gk.eucap", "gk.festival",
        "gk.landlocked", "gk.mobilegame", "gk.nutrient", "gk.olympic", "gk.queen", "gk.range", "gk.revolution", "gk.sea",
        "gk.solar", "gk.wonder", "his.civ", "his.emperor", "his.empire", "his.england", "his.explorer", "his.invention",
        "his.philosopher", "his.president", "his.war", "life.afraid", "life.airport", "life.argue", "life.awake",
        "life.badhabit", "life.batteries", "life.beach", "life.betterage", "life.birthday", "life.bored", "life.breakup",
        "life.broken", "life.buttons", "life.camping", "life.cancel", "life.cantsleep", "life.car", "life.celebrate",
        "life.chore", "life.classroom", "life.cold", "life.cry", "life.dog", "life.endearment", "life.firstdate",
        "life.fit", "life.flight", "life.flightdelay", "life.forgetpack", "life.friday", "life.fridge", "life.funfair",
        "life.gift", "life.giftnobody", "life.goodbye", "life.grows", "life.handbag", "life.hello", "life.hiccups",
        "life.holiday", "life.homework", "life.hot", "life.hotel", "life.interview", "life.junkdrawer", "life.kidsparty",
        "life.lastminute", "life.late", "life.leavehouse", "life.liars", "life.lose", "life.lottery", "life.morning",
        "life.move", "life.movecity", "life.nervous", "life.nightwake", "life.noreply", "life.nye", "life.old",
        "life.onehand", "life.pairs", "life.parentsay", "life.park", "life.party", "life.phone", "life.picnic",
        "life.powercut", "life.procrastinate", "life.quiet", "life.quit", "life.rainy", "life.relax", "life.resolution",
        "life.retire", "life.roadtrip", "life.round", "life.savemoney", "life.screen", "life.shout", "life.shower",
        "life.sick", "life.snow", "life.sofa", "life.soup", "life.sticky", "life.summer", "life.superpower",
        "life.surprised", "life.toast", "life.traffic", "life.transport", "life.waiting", "life.wakesound", "life.wedding",
        "life.weekend", "life.wheels", "life.winter", "misc.appliance", "misc.bodypart", "misc.brand", "misc.car",
        "misc.clothing", "misc.company", "misc.emotion", "misc.font", "misc.furniture", "misc.gem", "misc.greek",
        "misc.hobby", "misc.holiday", "misc.jewellery", "misc.job", "misc.material", "misc.metal", "misc.room",
        "misc.school", "misc.ship", "misc.shoe", "misc.tool", "misc.toy", "misc.vehicle", "misc.weapon", "misc.zodiac",
        "mov.vehicle", "mov.villain", "nat.african", "nat.amphibian", "nat.bird", "nat.disaster", "nat.dog", "nat.farm",
        "nat.fish", "nat.flower", "nat.insect", "nat.mammal", "nat.marine", "nat.mushroom", "nat.pet", "nat.primate",
        "nat.raptor", "nat.reptile", "nat.snake", "nat.tree", "occ.author", "occ.creature", "occ.cryptid", "occ.demon",
        "occ.divination", "occ.film", "occ.horrorgame", "occ.phobia", "occ.poison", "occ.tarot", "occ.tv",
        "occ.underworld", "occ.villain", "pop.detective", "pop.duo", "pop.pirate", "pop.robot", "sci.bone", "sci.cloud",
        "sci.constellation", "sci.dino", "sci.disease", "sci.element", "sci.mathematician", "sci.moon", "sci.muscle",
        "sci.ology", "sci.organ", "sci.particle", "sci.period", "sci.physicist", "sci.rock", "sci.scientist", "sci.shape",
        "sci.unit", "sport.athlete", "sport.gear", "sport.martial", "sport.olympicsport", "sport.sport", "sport.team",
        "sport.yoga", "tech.app", "tech.brand", "tech.computer", "tech.console", "tv.gameshow", "tv.host", "tv.reality"
    )
}
