package com.mantz_it.rfanalyzer.control;

/**
 * Created by Pavel on 20.03.2017.
 */

import java.util.Collection;
import java.util.List;

/**
 * Interface for entities that can be controlled in multiple ways.
 */
public interface Controllable {
/**
 * Get specific {@link com.mantz_it.rfanalyzer.control.Control} over this entity, can be null if not supported.
 * @return
 */
<T extends Control> T getControl(Class<T> clazz);

/**
 * Get all supported {@link com.mantz_it.rfanalyzer.control.Control}s supported by this entity.
 * @return
 */
Collection<Control> getControls();
}
