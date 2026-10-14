/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.eisi05.npc.api.utils.serialize;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adapts values whose runtime type may differ from their declaration type. This is necessary when a field's type is not the same type that GSON should create
 * when deserializing that field.
 */
public final class RuntimeTypeAdapterFactory<T> implements TypeAdapterFactory
{
    private final Class<?> baseType;
    private final String typeFieldName;
    private final Map<String, Class<?>> labelToSubtype = new LinkedHashMap<>();
    private final Map<Class<?>, String> subtypeToLabel = new LinkedHashMap<>();
    private final boolean maintainType;

    private RuntimeTypeAdapterFactory(Class<?> baseType, String typeFieldName, boolean maintainType)
    {
        if(typeFieldName == null || baseType == null)
            throw new NullPointerException();

        this.baseType = baseType;
        this.typeFieldName = typeFieldName;
        this.maintainType = maintainType;
    }

    /**
     * Creates a new runtime type adapter for {@code baseType} using {@code typeFieldName} as the type field name. Type field names are case sensitive.
     *
     * @param maintainType true if the type field should be included in deserialized objects
     */
    public static <T> RuntimeTypeAdapterFactory<T> of(Class<T> baseType, String typeFieldName, boolean maintainType)
    {
        return new RuntimeTypeAdapterFactory<>(baseType, typeFieldName, maintainType);
    }

    /**
     * Creates a new runtime type adapter for {@code baseType} using {@code typeFieldName} as the type field name. Type field names are case sensitive.
     */
    public static <T> RuntimeTypeAdapterFactory<T> of(Class<T> baseType, String typeFieldName)
    {
        return new RuntimeTypeAdapterFactory<>(baseType, typeFieldName, false);
    }

    /**
     * Creates a new runtime type adapter for {@code baseType} using {@code "type"} as the type field name.
     */
    public static <T> RuntimeTypeAdapterFactory<T> of(Class<T> baseType)
    {
        return new RuntimeTypeAdapterFactory<>(baseType, "type", false);
    }

    /**
     * Registers {@code type} identified by {@code label}. Labels are case sensitive.
     *
     * @throws IllegalArgumentException if either {@code type} or {@code label} have already been registered on this type adapter.
     */
    @CanIgnoreReturnValue
    public RuntimeTypeAdapterFactory<T> registerSubtype(Class<? extends T> type, String label)
    {
        if(type == null || label == null)
            throw new NullPointerException();

        if(subtypeToLabel.containsKey(type) || labelToSubtype.containsKey(label))
            throw new IllegalArgumentException("types and labels must be unique");

        labelToSubtype.put(label, type);
        subtypeToLabel.put(type, label);
        return this;
    }

    @Override
    public <R> TypeAdapter<R> create(Gson gson, TypeToken<R> type)
    {
        if(type == null)
            return null;

        Class<?> rawType = type.getRawType();
        boolean handle = baseType.equals(rawType);
        if(!handle)
            return null;

        TypeAdapter<JsonElement> jsonElementAdapter = gson.getAdapter(JsonElement.class);
        Map<String, TypeAdapter<?>> labelToDelegate = new LinkedHashMap<>();
        Map<Class<?>, TypeAdapter<?>> subtypeToDelegate = new LinkedHashMap<>();
        for(Map.Entry<String, Class<?>> entry : labelToSubtype.entrySet())
        {
            TypeAdapter<?> delegate = gson.getDelegateAdapter(this, TypeToken.get(entry.getValue()));
            labelToDelegate.put(entry.getKey(), delegate);
            subtypeToDelegate.put(entry.getValue(), delegate);
        }

        return new TypeAdapter<R>()
        {
            @Override
            public R read(JsonReader in) throws IOException
            {
                JsonElement jsonElement = jsonElementAdapter.read(in);
                JsonElement labelJsonElement;
                if(maintainType)
                    labelJsonElement = jsonElement.getAsJsonObject().get(typeFieldName);
                else
                    labelJsonElement = jsonElement.getAsJsonObject().remove(typeFieldName);

                if(labelJsonElement == null)
                    throw new JsonParseException("cannot deserialize " + baseType + " because it does not define a field named " + typeFieldName);

                String label = labelJsonElement.getAsString();
                @SuppressWarnings("unchecked") // registration requires that subtype extends T
                TypeAdapter<R> delegate = (TypeAdapter<R>) labelToDelegate.get(label);
                if(delegate == null)
                    throw new JsonParseException("cannot deserialize " + baseType + " subtype named " + label + "; did you forget to register a subtype?");

                return delegate.fromJsonTree(jsonElement);
            }

            @Override
            public void write(JsonWriter out, R value) throws IOException
            {
                Class<?> srcType = value.getClass();
                String label = subtypeToLabel.get(srcType);
                @SuppressWarnings("unchecked") // registration requires that subtype extends T
                TypeAdapter<R> delegate = (TypeAdapter<R>) subtypeToDelegate.get(srcType);
                if(delegate == null)
                    throw new JsonParseException("cannot serialize " + srcType.getName() + "; did you forget to register a subtype?");
                JsonObject jsonObject = delegate.toJsonTree(value).getAsJsonObject();

                if(maintainType)
                {
                    jsonElementAdapter.write(out, jsonObject);
                    return;
                }

                JsonObject clone = new JsonObject();

                if(jsonObject.has(typeFieldName))
                    throw new JsonParseException("cannot serialize " + srcType.getName() + " because it already defines a field named " + typeFieldName);

                clone.add(typeFieldName, new JsonPrimitive(label));

                for(Map.Entry<String, JsonElement> e : jsonObject.entrySet())
                    clone.add(e.getKey(), e.getValue());
                jsonElementAdapter.write(out, clone);
            }
        }.nullSafe();
    }
}