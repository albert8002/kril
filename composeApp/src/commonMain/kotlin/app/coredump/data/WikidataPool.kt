package app.coredump.data

/**
 * Extra Wikidata lists that are NOT bundled: the device collects a few of them every day
 * (and on request), so the library keeps growing with genuinely new prompts.
 *
 * People, countries and studios are matched by their English label (robust against wrong ids);
 * lists with fewer than [WikidataFamilies.MIN_ANSWERS] answers are skipped and not retried for weeks.
 */
object WikidataPool {

    private fun slug(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun esc(s: String) = s.replace("\\", "").replace("\"", "\\\"")

    /** Binds ?who to the human with this English label. */
    private fun person(name: String, v: String = "who") = "?$v rdfs:label \"${esc(name)}\"@en ; wdt:P31 wd:Q5 ."

    /** Binds ?ctry to the sovereign state with this English label. */
    private fun country(name: String) = "?ctry rdfs:label \"${esc(name)}\"@en ; wdt:P31 wd:Q6256 ."

    private fun t(id: String, prompt: String, topic: String, where: String, minLinks: Int = 0, limit: Int = 800) =
        WikidataTemplate("wx.$id", prompt, topic, where, minLinks, limit)

    private val actors = listOf(
        "Tom Hanks", "Robert De Niro", "Meryl Streep", "Nicolas Cage", "Samuel L. Jackson", "Morgan Freeman",
        "Johnny Depp", "Brad Pitt", "Leonardo DiCaprio", "Denzel Washington", "Tom Cruise", "Al Pacino",
        "Jack Nicholson", "Harrison Ford", "Bruce Willis", "Arnold Schwarzenegger", "Sylvester Stallone",
        "Keanu Reeves", "Scarlett Johansson", "Cate Blanchett", "Nicole Kidman", "Julia Roberts", "Sandra Bullock",
        "Matt Damon", "Christian Bale", "Gary Oldman", "Michael Caine", "Anthony Hopkins", "Christopher Walken",
        "Willem Dafoe", "Jim Carrey", "Robin Williams", "Eddie Murphy", "Adam Sandler", "Ben Stiller", "Owen Wilson",
        "Bill Murray", "Steve Buscemi", "John Goodman", "Kevin Bacon", "Gene Hackman", "Dustin Hoffman",
        "Jackie Chan", "Toshiro Mifune", "Gérard Depardieu", "Catherine Deneuve", "Isabelle Huppert",
        "Marcello Mastroianni", "Sophia Loren", "Christopher Lee", "Peter Cushing", "Vincent Price", "Bela Lugosi",
        "Boris Karloff", "Jamie Lee Curtis", "Sigourney Weaver", "Bruce Campbell", "Danny Trejo", "Shah Rukh Khan",
        "Amitabh Bachchan", "Humphrey Bogart", "James Stewart", "Cary Grant", "Marilyn Monroe", "Audrey Hepburn",
    )

    private val directors = listOf(
        "Stanley Kubrick", "Quentin Tarantino", "Christopher Nolan", "David Lynch", "Wes Anderson", "Joel Coen",
        "Guillermo del Toro", "Sam Raimi", "Wes Craven", "George A. Romero", "Dario Argento", "Lucio Fulci",
        "Roger Corman", "John Woo", "Hayao Miyazaki", "Ang Lee", "Spike Lee", "Pedro Almodóvar", "Werner Herzog",
        "Rainer Werner Fassbinder", "Roman Polanski", "Brian De Palma", "Oliver Stone", "Ron Howard",
        "Robert Zemeckis", "James Cameron", "Peter Jackson", "Michael Bay", "Tony Scott", "Sidney Lumet",
        "Billy Wilder", "Orson Welles", "John Ford", "Howard Hawks", "Fritz Lang", "Sergio Leone", "Luis Buñuel",
        "François Truffaut", "Yasujirō Ozu", "Takashi Miike", "Park Chan-wook", "Bong Joon-ho", "Wong Kar-wai",
        "Zhang Yimou", "Satyajit Ray", "Andrei Tarkovsky", "Lars von Trier", "Michael Haneke", "Krzysztof Kieślowski",
        "Mike Leigh", "Ken Loach", "Danny Boyle", "Guy Ritchie", "Richard Linklater", "Jim Jarmusch", "Terry Gilliam",
        "Mel Brooks", "John Hughes", "Rob Reiner", "Steven Soderbergh", "David Fincher", "Paul Thomas Anderson",
        "Denis Villeneuve", "Claude Chabrol", "Éric Rohmer", "Agnès Varda", "Vittorio De Sica", "Pier Paolo Pasolini",
    )

    private val authors = listOf(
        "Stephen King", "Agatha Christie", "Terry Pratchett", "Isaac Asimov", "Jules Verne", "Charles Dickens",
        "Arthur Conan Doyle", "H. P. Lovecraft", "Edgar Allan Poe", "Mark Twain", "Leo Tolstoy", "Fyodor Dostoevsky",
        "Honoré de Balzac", "Victor Hugo", "Émile Zola", "Alexandre Dumas", "H. G. Wells", "Philip K. Dick",
        "Ray Bradbury", "Ursula K. Le Guin", "Neil Gaiman", "J. R. R. Tolkien", "C. S. Lewis", "Roald Dahl",
        "Enid Blyton", "P. G. Wodehouse", "Georges Simenon", "Haruki Murakami", "Gabriel García Márquez",
        "Jorge Luis Borges", "Hermann Hesse", "Thomas Mann", "Franz Kafka", "Virginia Woolf", "Ernest Hemingway",
        "William Faulkner", "John Steinbeck", "Kurt Vonnegut", "Dean Koontz", "Clive Barker", "Anne Rice",
        "Shirley Jackson", "Michael Crichton", "Tom Clancy", "John Grisham", "Arthur C. Clarke", "Robert A. Heinlein",
        "William Shakespeare", "Anton Chekhov", "Oscar Wilde", "Rudyard Kipling", "Jack London", "Edith Wharton",
        "Henry James", "Ian Fleming", "Raymond Chandler", "Dashiell Hammett", "Patricia Highsmith", "Bram Stoker",
    )

    private val musicians = listOf(
        "The Beatles", "Queen", "ABBA", "Madonna", "Michael Jackson", "Elvis Presley", "David Bowie", "Prince",
        "The Rolling Stones", "Led Zeppelin", "Pink Floyd", "Metallica", "Iron Maiden", "Black Sabbath", "AC/DC",
        "Nirvana", "Radiohead", "U2", "Coldplay", "Taylor Swift", "Beyoncé", "Rihanna", "Lady Gaga", "Eminem",
        "Kanye West", "Drake", "Bob Dylan", "Bruce Springsteen", "Elton John", "Johnny Cash", "Dolly Parton",
        "Kylie Minogue", "Britney Spears", "Daft Punk", "Depeche Mode", "The Cure", "The Smiths", "Kraftwerk",
        "Rammstein", "Muse", "Linkin Park", "Green Day", "Red Hot Chili Peppers", "Pearl Jam", "Foo Fighters",
        "Oasis", "Blur", "Arctic Monkeys", "The Doors", "Jimi Hendrix", "Bob Marley", "Aretha Franklin",
        "Whitney Houston", "Mariah Carey", "Celine Dion", "Shakira", "BTS", "Blackpink", "Frank Sinatra",
        "Stevie Wonder", "The Beach Boys", "Fleetwood Mac", "Kiss", "Slayer", "Megadeth", "Judas Priest",
    )

    private val painters = listOf(
        "Vincent van Gogh", "Claude Monet", "Pablo Picasso", "Rembrandt", "Johannes Vermeer", "Caravaggio",
        "Salvador Dalí", "René Magritte", "Edvard Munch", "Gustav Klimt", "Egon Schiele", "Paul Cézanne",
        "Paul Gauguin", "Edgar Degas", "Pierre-Auguste Renoir", "Édouard Manet", "J. M. W. Turner", "John Constable",
        "Francisco Goya", "Diego Velázquez", "El Greco", "Titian", "Raphael", "Sandro Botticelli",
        "Hieronymus Bosch", "Pieter Bruegel the Elder", "Albrecht Dürer", "Caspar David Friedrich",
        "Wassily Kandinsky", "Paul Klee", "Joan Miró", "Henri Matisse", "Edward Hopper", "Frida Kahlo",
        "Camille Pissarro", "Alfred Sisley", "Peter Paul Rubens", "Anthony van Dyck", "Frans Hals", "Jan van Eyck",
    )

    private val studios = listOf(
        "Nintendo", "Capcom", "Konami", "Square Enix", "Square", "Sega", "Ubisoft", "Electronic Arts",
        "Blizzard Entertainment", "Valve Corporation", "id Software", "Bethesda Game Studios", "BioWare",
        "Rockstar North", "Naughty Dog", "Insomniac Games", "Namco", "Taito", "Activision", "FromSoftware", "Atlus",
        "Hudson Soft", "Rare", "HAL Laboratory", "Game Freak", "Intelligent Systems", "Treasure", "SNK", "Irem",
        "Codemasters", "Remedy Entertainment", "CD Projekt Red", "Firaxis Games", "Sierra On-Line", "LucasArts",
        "Infocom", "Westwood Studios", "Epic Games", "Telltale Games", "Double Fine",
    )

    private val moreCountries = listOf(
        "Algeria", "Angola", "Bangladesh", "Belarus", "Bolivia", "Bulgaria", "Cameroon", "Croatia", "Cuba",
        "Dominican Republic", "Ecuador", "Ethiopia", "Ghana", "Guatemala", "Iraq", "Kazakhstan", "Malaysia",
        "Myanmar", "Nepal", "Serbia", "Slovakia", "Sri Lanka", "Sudan", "Syria", "Tanzania", "Tunisia", "Uganda",
        "Uzbekistan", "Venezuela", "Zimbabwe", "Afghanistan", "Azerbaijan", "Lithuania", "Latvia", "Estonia",
        "Slovenia", "Bosnia and Herzegovina", "North Macedonia", "Albania", "Georgia", "Armenia", "Jordan",
        "Lebanon", "Yemen", "Libya", "Senegal", "Mozambique", "Madagascar", "Ivory Coast", "Paraguay", "Uruguay",
        "Costa Rica", "Panama", "Honduras", "Cambodia", "Mongolia", "Iceland", "Luxembourg", "Cyprus", "Malta",
    )

    private val bigCountries = listOf(
        "United States", "United Kingdom", "France", "Germany", "Italy", "Spain", "Japan", "China", "India", "Brazil",
        "Canada", "Australia", "Mexico", "Russia", "Netherlands", "Sweden", "Poland", "Argentina", "South Korea",
        "Turkey", "Ireland", "Austria", "Belgium", "Switzerland", "Norway", "Denmark", "Finland", "Greece",
        "Portugal", "Hungary", "Czech Republic", "Romania", "Ukraine", "Israel", "Egypt", "Nigeria", "South Africa",
        "Iran", "Indonesia", "Philippines",
    )

    private val occupations = listOf(
        "Q33999" to "actor", "Q177220" to "singer", "Q36180" to "writer", "Q1028181" to "painter",
        "Q36834" to "composer", "Q82955" to "politician", "Q169470" to "physicist", "Q170790" to "mathematician",
        "Q2526255" to "film director", "Q42973" to "architect", "Q49757" to "poet", "Q937857" to "footballer",
        "Q10833314" to "tennis player", "Q10873124" to "chess player", "Q33231" to "photographer",
        "Q4964182" to "philosopher", "Q593644" to "chemist", "Q1622272" to "university teacher",
    )

    val all: List<WikidataTemplate> by lazy {
        buildList {
            for (a in actors) add(t("star.${slug(a)}", "Name a film starring $a", "CULTURE",
                "${person(a)} ?item wdt:P161 ?who ; wdt:P31/wdt:P279* wd:Q11424 ."))
            for (d in directors) add(t("dir.${slug(d)}", "Name a film directed by $d", "CULTURE",
                "${person(d)} ?item wdt:P57 ?who ; wdt:P31/wdt:P279* wd:Q11424 ."))
            for (w in authors) add(t("by.${slug(w)}", "Name a work by $w", "CULTURE",
                "${person(w)} ?item wdt:P50 ?who ."))
            for (m in musicians) {
                add(t("song.${slug(m)}", "Name a song by $m", "CULTURE",
                    "?who rdfs:label \"${esc(m)}\"@en . VALUES ?k { wd:Q7366 wd:Q134556 } ?item wdt:P175 ?who ; wdt:P31 ?k ."))
                add(t("album.${slug(m)}", "Name an album by $m", "CULTURE",
                    "?who rdfs:label \"${esc(m)}\"@en . ?item wdt:P175 ?who ; wdt:P31 wd:Q482994 ."))
            }
            for (p in painters) add(t("art.${slug(p)}", "Name a painting by $p", "CULTURE",
                "${person(p)} ?item wdt:P170 ?who ; wdt:P31 wd:Q3305213 ."))
            for (s in studios) add(t("dev.${slug(s)}", "Name a video game developed by $s", "CULTURE",
                "?dev rdfs:label \"${esc(s)}\"@en . ?item wdt:P178 ?dev ; wdt:P31 wd:Q7889 ."))
            for (c in moreCountries) {
                add(t("city.${slug(c)}", "Name a city or town in $c", "GEOGRAPHY",
                    "${country(c)} ?item wdt:P17 ?ctry ; wdt:P31/wdt:P279* wd:Q515 ."))
                add(t("river.${slug(c)}", "Name a river in $c", "GEOGRAPHY",
                    "${country(c)} ?item wdt:P17 ?ctry ; wdt:P31 wd:Q4022 ."))
                add(t("mountain.${slug(c)}", "Name a mountain in $c", "GEOGRAPHY",
                    "${country(c)} ?item wdt:P17 ?ctry ; wdt:P31 wd:Q8502 ."))
            }
            for (c in bigCountries) {
                add(t("park.${slug(c)}", "Name a national park in $c", "GEOGRAPHY",
                    "${country(c)} ?item wdt:P17 ?ctry ; wdt:P31 wd:Q46169 ."))
                add(t("club.${slug(c)}", "Name a football club from $c", "SPORT",
                    "${country(c)} ?item wdt:P17 ?ctry ; wdt:P31 wd:Q476028 .", minLinks = 3))
                add(t("museum.${slug(c)}", "Name a museum in $c", "HISTORY",
                    "${country(c)} ?item wdt:P17 ?ctry ; wdt:P31/wdt:P279* wd:Q33506 .", minLinks = 3))
                for ((q, occ) in occupations) add(t("occ.${slug(occ)}.${slug(c)}", "Name a famous $occ from $c",
                    if (occ == "footballer" || occ.endsWith("player")) "SPORT" else "PEOPLE",
                    "${country(c)} ?item wdt:P31 wd:Q5 ; wdt:P106 wd:$q ; wdt:P27 ?ctry .", minLinks = 8))
            }
        }
    }

    /**
     * Order in which the device works through the pool: interleaves the families
     * (film, song, city, people, …) so each daily batch is varied.
     */
    val order: List<WikidataTemplate> by lazy {
        val groups = all.groupBy { it.id.split('.').getOrElse(1) { "" } }.values.map { it.toMutableList() }
        val out = ArrayList<WikidataTemplate>(all.size)
        var i = 0
        while (out.size < all.size) {
            for (g in groups) if (i < g.size) out += g[i]
            i++
        }
        out
    }
}
