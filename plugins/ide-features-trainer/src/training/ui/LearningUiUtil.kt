// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package training.ui

import com.intellij.openapi.wm.impl.IdeFrameImpl
import com.intellij.util.ui.UIUtil
import org.fest.swing.core.GenericTypeMatcher
import org.fest.swing.core.Robot
import org.fest.swing.exception.ComponentLookupException
import org.fest.swing.exception.WaitTimedOutError
import org.fest.swing.fixture.ContainerFixture
import org.fest.swing.timing.Condition
import org.fest.swing.timing.Pause
import org.fest.swing.timing.Timeout
import java.awt.Component
import java.awt.Container
import java.awt.Rectangle
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** It is a copy-paste from testGuiFramework to use minimal necessary subset to discover UI elements */
object LearningUiUtil {
  @Volatile
  private var myRobot: Robot? = null

  val robot: Robot
    get() {
      if (myRobot == null)
        synchronized(this) {
          if (myRobot == null) initializeRobot()
        }
      return myRobot ?: throw IllegalStateException("Cannot initialize the robot")
    }

  private fun initializeRobot() {
    if (myRobot != null) releaseRobot()
    myRobot = IftSmartWaitRobot()
  }

  private fun releaseRobot() {
    if (myRobot != null) {
      synchronized(this) {
        if (myRobot != null) {
          myRobot!!.cleanUpWithoutDisposingWindows()  // releases ScreenLock
          myRobot = null
        }
      }
    }
  }

  /**
   * Waits for a first component which passes the given matcher under the given root to become visible.
   */
  private fun <T : Component> waitUntilFoundAll(robot: Robot,
                                                root: Container?,
                                                matcher: GenericTypeMatcher<T>,
                                                timeout: Timeout): Collection<T> {
    val reference = AtomicReference<Collection<T>>()
    Pause.pause(object : Condition("Find component using $matcher") {
      override fun test(): Boolean {
        val finder = robot.finder()
        val allFound = if (root != null) finder.findAll(root, matcher) else finder.findAll(matcher)
        if (allFound.isNotEmpty()) {
          reference.set(allFound)
          return true
        }
        return false
      }
    }, timeout)

    return reference.get()
  }

  fun <T : Component> waitUntilFound(robot: Robot,
                                     root: Container?,
                                     matcher: GenericTypeMatcher<T>,
                                     timeout: Timeout): T {
    val allFound = waitUntilFoundAll(robot, root, matcher, timeout)
    if (allFound.size > 1) {
      // Only allow a single component to be found, otherwise you can get some really confusing
      // test failures; the matcher should pick a specific enough instance
      throw ComponentLookupException(
        "Found more than one " + matcher.supportedType().simpleName + " which matches the criteria: " + allFound)
    }
    return allFound.single()
  }

  fun <ComponentType : Component?> typeMatcher(componentTypeClass: Class<ComponentType>,
                                               matcher: (ComponentType) -> Boolean): GenericTypeMatcher<ComponentType> {
    return object : GenericTypeMatcher<ComponentType>(componentTypeClass) {
      override fun isMatching(component: ComponentType): Boolean = matcher(component)
    }
  }

  private fun isReallyVisible(component: Component): Boolean {
    val frame = UIUtil.getParentOfType(IdeFrameImpl::class.java, component) ?: return true
    val locationOnScreen = component.locationOnScreen
    val onScreenRect = Rectangle(locationOnScreen.x, locationOnScreen.y, component.width, component.height)
    val bounds = frame.bounds
    return bounds.intersects(onScreenRect)
  }

  fun <ComponentType : Component> findShowingComponentWithTimeout(container: Container?,
                                                                  componentClass: Class<ComponentType>,
                                                                  timeout: Timeout = Timeout.timeout(10, TimeUnit.SECONDS),
                                                                  selector: ((candidates: Collection<ComponentType>) -> ComponentType?)? = null,
                                                                  finderFunction: (ComponentType) -> Boolean = { true }): ComponentType {
    try {
      val matcher = typeMatcher(componentClass) {
        it.isShowing && finderFunction(it) && isReallyVisible(it)
      }
      return if (selector != null) {
        val result = waitUntilFoundAll(robot, container, matcher, timeout)
        selector(result) ?: throw ComponentLookupException("Cannot filter result component from: $result")
      }
      else {
        waitUntilFound(robot, container, matcher, timeout)
      }
    }
    catch (e: WaitTimedOutError) {
      throw ComponentLookupException(
        "Unable to find ${componentClass.simpleName} ${if (container != null) "in container $container" else ""} in ${timeout.duration()}(ms)")
    }
  }

  fun <ComponentType : Component> findAllShowingComponentWithTimeout(container: Container?,
                                                                     componentClass: Class<ComponentType>,
                                                                     timeout: Timeout = Timeout.timeout(10, TimeUnit.SECONDS),
                                                                     finderFunction: (ComponentType) -> Boolean = { true }): Collection<ComponentType> {
    try {
      val matcher = typeMatcher(componentClass) {
        it.isShowing && finderFunction(it) && isReallyVisible(it)
      }
      return waitUntilFoundAll(robot, container, matcher, timeout)
    }
    catch (e: WaitTimedOutError) {
      throw ComponentLookupException(
        "Unable to find ${componentClass.simpleName} ${if (container != null) "in container $container" else ""} in ${timeout.duration()}(ms)")
    }
  }

  /**
   * function to find component of returning type inside a container (gets from receiver).
   *
   * @throws ComponentLookupException if desired component haven't been found under the container (gets from receiver) in specified timeout
   */
  inline fun <reified ComponentType : Component, ContainerComponentType : Container> ContainerFixture<ContainerComponentType>?.findComponentWithTimeout(
    timeout: Timeout = Timeout.timeout(10, TimeUnit.SECONDS),
    crossinline finderFunction: (ComponentType) -> Boolean = { true }): ComponentType {
    try {
      return waitUntilFound(robot,
                            this?.target() as Container?,
                            typeMatcher(ComponentType::class.java) { finderFunction(it) },
                            timeout)
    }
    catch (e: WaitTimedOutError) {
      throw ComponentLookupException(
        "Unable to find ${ComponentType::class.java.name} ${if (this?.target() != null) "in container ${this.target()}" else ""} in ${timeout.duration()}")
    }
  }

  fun <ComponentType : Component> findComponentOrNull(componentClass: Class<ComponentType>,
                                                      selector: ((candidates: Collection<ComponentType>) -> ComponentType?)? = null,
                                                      finderFunction: (ComponentType) -> Boolean = { true }): ComponentType? {
    val delay = Timeout.timeout(500, TimeUnit.MILLISECONDS)
    return try {
      findShowingComponentWithTimeout(null, componentClass, delay, selector, finderFunction)
    }
    catch (e: WaitTimedOutError) {
      null
    }
    catch (e: ComponentLookupException) {
      null
    }
  }
}