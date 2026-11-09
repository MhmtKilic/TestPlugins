package com.example

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class FilmIzlesene : MainAPI() {
    override var mainUrl = "https://www.4kfilmizlesene.xyz"
    // ... geri kalan kod aynı ...
