package com.berkacaki

import com.google.firebase.database.FirebaseDatabase

fun getAppDatabase(): FirebaseDatabase {
    return FirebaseDatabase.getInstance("https://berkacaki-default-rtdb.asia-southeast1.firebasedatabase.app")
}