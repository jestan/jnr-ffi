/*
 * Copyright (C) 2008-2010 Wayne Meissner
 *
 * This file is part of the JNR project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jnr.ffi.mapper;

import jnr.ffi.Library;

import java.lang.annotation.Annotation;
import java.util.Collection;


public interface FunctionMapper {
    public static interface Context {
        @Deprecated
        public abstract Library getLibrary();
        public abstract boolean isSymbolPresent(String name);
        public Collection<Annotation> getAnnotations();
    }
    public String mapFunctionName(String functionName, Context context);
}
