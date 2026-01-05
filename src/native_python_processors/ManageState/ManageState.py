from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope, PropertyDependency
import json
import os
import fcntl
import time

class ManageState(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.3-SNAPSHOT'
        description = """Manages state (Global Variables) in:
        1. NiFi State Manager (Component Scope)
        2. JSON File (File System Scope)
        3. Distributed Map Cache (Cluster Scope via Service)
        Supports SET, GET, and DELETE operations."""
        tags = ['state', 'variable', 'global', 'store', 'python', 'json', 'file', 'cache', 'map']

    def __init__(self, **kwargs):
        pass

    OPERATION = PropertyDescriptor(
        name="Operation",
        description="The operation to perform (GET, SET, DELETE).",
        required=True,
        default_value="GET",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    STORAGE_METHOD = PropertyDescriptor(
        name="Storage Method",
        description="Where to store the state.",
        required=True,
        default_value="NiFi State Manager",
        allowable_values=["NiFi State Manager", "JSON File", "Map Cache Client"]
    )

    FILE_PATH = PropertyDescriptor(
        name="File Path",
        description="The full path to the JSON file used for storage. Required if Storage Method is 'JSON File'.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        dependencies=[PropertyDependency(STORAGE_METHOD, "JSON File")]
    )

    MAP_CACHE_SERVICE = PropertyDescriptor(
        name="Map Cache Service",
        description="The Distributed Map Cache Client service to use for storage.",
        required=False,
        controller_service_definition="org.apache.nifi.distributed.cache.client.DistributedMapCacheClient",
        dependencies=[PropertyDependency(STORAGE_METHOD, "Map Cache Client")]
    )

    SCOPE = PropertyDescriptor(
        name="State Scope",
        description="The scope of the state (CLUSTER or LOCAL). Only applies to NiFi State Manager.",
        required=True,
        default_value="CLUSTER",
        allowable_values=["CLUSTER", "LOCAL"],
        dependencies=[PropertyDependency(STORAGE_METHOD, "NiFi State Manager")]
    )

    STATE_KEY = PropertyDescriptor(
        name="State Key",
        description="The key of the state variable to interact with.",
        required=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR],
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    STATE_VALUE = PropertyDescriptor(
        name="State Value",
        description="The value to set (required for SET operation).",
        required=False,
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES
    )

    OUTPUT_ATTRIBUTE = PropertyDescriptor(
        name="Output Attribute",
        description="The attribute to store the retrieved value in (for GET operation).",
        required=True,
        default_value="state.value",
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    def getPropertyDescriptors(self):
        return [
            self.OPERATION, 
            self.STORAGE_METHOD, 
            self.FILE_PATH,
            self.MAP_CACHE_SERVICE,
            self.SCOPE, 
            self.STATE_KEY, 
            self.STATE_VALUE, 
            self.OUTPUT_ATTRIBUTE
        ]

    def transform(self, context, flowFile):
        try:
            # Evaluate Operation with Expression Language
            operation = context.getProperty(self.OPERATION).evaluateAttributeExpressions(flowFile).getValue()
            if operation:
                operation = operation.upper().strip()
            
            if operation not in ["GET", "SET", "DELETE"]:
                self.logger.error(f"Invalid Operation: '{operation}'. Must be GET, SET, or DELETE.")
                return FlowFileTransformResult(relationship="failure")

            storage_method = context.getProperty(self.STORAGE_METHOD).getValue()
            key = context.getProperty(self.STATE_KEY).evaluateAttributeExpressions(flowFile).getValue()
            
            if not key:
                self.logger.error("State Key cannot be empty.")
                return FlowFileTransformResult(relationship="failure")

            if storage_method == "JSON File":
                return self._handle_json_file(context, flowFile, operation, key)
            elif storage_method == "Map Cache Client":
                return self._handle_map_cache(context, flowFile, operation, key)
            else:
                return self._handle_nifi_state_manager(context, flowFile, operation, key)

        except Exception as e:
            self.logger.error(f"ManageState failed: {str(e)}")
            return FlowFileTransformResult(relationship="failure")

    def _handle_map_cache(self, context, flowFile, operation, key):
        cache_service = context.getProperty(self.MAP_CACHE_SERVICE).asControllerService()
        if not cache_service:
            self.logger.error("Map Cache Service property is not set or valid.")
            return FlowFileTransformResult(relationship="failure")

        # Since standard StringSerializer is not in the API JAR, we implement it in Python.
        # Py4J allows Python classes to implement Java interfaces.
        
        class PythonStringSerializer:
            def serialize(self, value, output_stream):
                if value is None:
                    return
                output_stream.write(value.encode('utf-8'))

            def deserialize(self, input_bytes):
                # The DistributedMapCacheClient Deserializer typically takes a byte[]
                if input_bytes is None:
                    return None
                return bytes(input_bytes).decode('utf-8')
            
            class Java:
               implements = [
                   "org.apache.nifi.distributed.cache.client.Serializer",
                   "org.apache.nifi.distributed.cache.client.Deserializer"
                ]

        serializer = PythonStringSerializer()
        
        try:
            if operation == "GET":
                # get(key, keySerializer, valueSerializer)
                # The service interface is typically <K, V> get(K key, Serializer<K> keySerializer, Deserializer<V> valueDeserializer)
                # StringSerializer implements both Serializer and Deserializer.
                
                # Careful: The method signature might rely on generics which Py4J handles, OR might differ slightly.
                # Common signature: V get(K key, Serializer<K> keySerializer, Deserializer<V> valueDeserializer)
                
                val = cache_service.get(key, serializer, serializer)
                if val is None:
                    val = ""
                
                output_attr = context.getProperty(self.OUTPUT_ATTRIBUTE).getValue()
                return FlowFileTransformResult(
                    relationship="success",
                    attributes={output_attr: str(val)}
                )
            elif operation == "SET":
                val_to_set = context.getProperty(self.STATE_VALUE).evaluateAttributeExpressions(flowFile).getValue()
                if val_to_set is None:
                    val_to_set = ""
                
                # put(K key, V value, Serializer<K> keySerializer, Serializer<V> valueSerializer)
                cache_service.put(key, str(val_to_set), serializer, serializer)
                return FlowFileTransformResult(relationship="success")

            elif operation == "DELETE":
                # remove(K key, Serializer<K> keySerializer)
                cache_service.remove(key, serializer)
                return FlowFileTransformResult(relationship="success")

        except Exception as e:
            self.logger.error(f"Map Cache Client Operation failed: {e}")
            return FlowFileTransformResult(relationship="failure")


    def _handle_json_file(self, context, flowFile, operation, key):
        file_path = context.getProperty(self.FILE_PATH).getValue()
        
        mode = 'r+'
        if not os.path.exists(file_path):
            try:
                with open(file_path, 'w') as f:
                    json.dump({}, f)
            except Exception as e:
                self.logger.error(f"Failed to initialize JSON file {file_path}: {e}")
                return FlowFileTransformResult(relationship="failure")
        
        try:
            with open(file_path, 'r+') as f:
                lock_type = fcntl.LOCK_SH if operation == "GET" else fcntl.LOCK_EX
                fcntl.flock(f, lock_type)
                
                try:
                    f.seek(0)
                    try:
                        data = json.load(f)
                    except json.JSONDecodeError:
                        data = {}
                    if not isinstance(data, dict):
                        data = {}

                    if operation == "GET":
                        val = data.get(key, "")
                        output_attr = context.getProperty(self.OUTPUT_ATTRIBUTE).getValue()
                        return FlowFileTransformResult(
                            relationship="success",
                            attributes={output_attr: str(val)}
                        )
                    
                    elif operation == "SET":
                        val_to_set = context.getProperty(self.STATE_VALUE).evaluateAttributeExpressions(flowFile).getValue()
                        if val_to_set is None:
                            val_to_set = ""
                        data[key] = str(val_to_set)
                        
                        f.seek(0)
                        f.truncate()
                        json.dump(data, f, indent=2)
                        f.flush()
                        os.fsync(f.fileno())
                        return FlowFileTransformResult(relationship="success")

                    elif operation == "DELETE":
                        if key in data:
                            del data[key]
                            f.seek(0)
                            f.truncate()
                            json.dump(data, f, indent=2)
                            f.flush()
                            os.fsync(f.fileno())
                        return FlowFileTransformResult(relationship="success")
                
                finally:
                    fcntl.flock(f, fcntl.LOCK_UN)
                    
        except Exception as e:
            self.logger.error(f"JSON File Operation failed: {e}")
            return FlowFileTransformResult(relationship="failure")


    def _handle_nifi_state_manager(self, context, flowFile, operation, key):
        scope_val = context.getProperty(self.SCOPE).getValue()
        
        scope = None
        try:
            cls_loader = context.java_context.getClass().getClassLoader()
            scope_class = cls_loader.loadClass("org.apache.nifi.components.state.Scope")
            scope_field = scope_class.getField(scope_val)
            scope = scope_field.get(None)
        except Exception as e:
            self.logger.error(f"Failed to resolve State Scope '{scope_val}'. Error: {str(e)}")
            return FlowFileTransformResult(relationship="failure")

        state_manager = context.getStateManager()

        if operation == "GET":
            state_map = state_manager.getState(scope)
            current_state = state_map.toMap()
            # Fix logging crash from previous step
            try:
                all_keys = list(current_state.keys())
                self.logger.debug(f"Current State Keys in {scope_val}: {all_keys}")
            except:
                pass

            val = current_state.get(key)
            if val is None:
                val = ""
            
            output_attr = context.getProperty(self.OUTPUT_ATTRIBUTE).getValue()
            return FlowFileTransformResult(
                relationship="success",
                attributes={output_attr: str(val)}
            )

        elif operation == "SET":
            val_to_set = context.getProperty(self.STATE_VALUE).evaluateAttributeExpressions(flowFile).getValue()
            if val_to_set is None:
                val_to_set = ""

            for _ in range(5):
                state_map = state_manager.getState(scope)
                current_dict = dict(state_map.toMap()) 
                current_dict[key] = str(val_to_set)
                
                try:
                    res = state_manager.replace(state_map, current_dict, scope)
                    success = True if res is None else res
                except Exception as e:
                    success = False
                
                if success:
                    return FlowFileTransformResult(relationship="success")

            self.logger.error("Failed to SET state after 5 attempts.")
            return FlowFileTransformResult(relationship="failure")

        elif operation == "DELETE":
            for _ in range(5):
                state_map = state_manager.getState(scope)
                current_dict = dict(state_map.toMap())
                
                if key not in current_dict:
                    return FlowFileTransformResult(relationship="success")
                
                del current_dict[key]
                
                try:
                    res = state_manager.replace(state_map, current_dict, scope)
                    success = True if res is None else res
                except Exception as e:
                    success = False

                if success:
                        return FlowFileTransformResult(relationship="success")

            return FlowFileTransformResult(relationship="failure")
        
        return FlowFileTransformResult(relationship="failure")
