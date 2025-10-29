package dev.eca.eca_intellij

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object Icons {
  @JvmField val ECA_LIGHT = IconLoader.getIcon("/icons/eca_light.svg", Icons::class.java)
  @JvmField val ECA_DARK = IconLoader.getIcon("/icons/eca_dark.svg", Icons::class.java)
}
