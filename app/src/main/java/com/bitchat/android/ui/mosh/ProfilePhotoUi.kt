package com.bitchat.android.ui.mosh

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bitchat.android.mosh.ProfilePhotoBook
import com.bitchat.android.mosh.ProfilePhotos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * My avatar with a camera badge. Tapping it picks a photo (Android's photo picker: no storage
 * permission, only the one photo chosen is shared with MoshGrid) or removes the current one.
 */
@Composable
fun MyPhotoAvatar(name: String, size: Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val photos by ProfilePhotos.images.collectAsState()
    val hasPhoto = photos.containsKey(ProfilePhotoBook.ME)
    var menu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { ProfilePhotos.setMine(context, uri) }
            Toast.makeText(context, if (ok) "Photo set. Friends get it next time they're in reach." else "Couldn't use that photo. Try another.",
                Toast.LENGTH_SHORT).show()
        }
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val cs = MaterialTheme.colorScheme
    Box {
        Box(Modifier.clip(CircleShape)
            .clickable(role = Role.Button) { if (hasPhoto) menu = true else pick() }
            .semantics { contentDescription = if (hasPhoto) "Change or remove your photo" else "Add a photo" }) {
            MoshAvatar(name.ifBlank { "?" }, ProfilePhotoBook.ME, size)
        }
        Box(Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(size * 0.36f).clip(CircleShape)
            .background(cs.primary).border(2.dp, cs.surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.padding(3.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Choose a new photo") }, onClick = { menu = false; pick() })
            DropdownMenuItem(text = { Text("Remove photo") }, onClick = { menu = false; ProfilePhotos.removeMine() })
        }
    }
}
