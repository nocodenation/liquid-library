from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope

class StatefulCounter(FlowFileTransform):
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1-SNAPSHOT'
        description = """A simple example processor that maintains a running count in the NiFi State Manager.
        Each incoming FlowFile increments the counter. The current value is added as an attribute."""
        tags = ['state', 'counter', 'python', 'example']

    def __init__(self, **kwargs):
        pass

    SCOPE = PropertyDescriptor(
        name="State Scope",
        description="The scope of the state (CLUSTER or LOCAL).",
        required=True,
        default_value="CLUSTER",
        allowable_values=["CLUSTER", "LOCAL"]
    )

    def getPropertyDescriptors(self):
        return [self.SCOPE]

    def transform(self, context, flowFile):
        try:
            # Determine Scope Name
            scope_val = context.getProperty(self.SCOPE).getValue() # "CLUSTER" or "LOCAL"

            # Resolve Scope Enum via Reflection
            scope = None
            try:
                # Use java_context to get the ClassLoader
                # The debug log confirmed 'java_context' exists on the ProcessContext wrapper
                cls_loader = context.java_context.getClass().getClassLoader()
                
                scope_class = cls_loader.loadClass("org.apache.nifi.components.state.Scope")
                scope_field = scope_class.getField(scope_val)
                scope = scope_field.get(None)
            except Exception as e:
                self.logger.error(f"Failed to resolve State Scope '{scope_val}'. java_context available: {hasattr(context, 'java_context')}. Error: {str(e)}")
                return FlowFileTransformResult(relationship="failure")

            # Get State Manager
            state_manager = context.getStateManager()
            
            # Simple optimistic locking loop (try up to 5 times)
            for i in range(5):
                # 1. Get current state
                state_map = state_manager.getState(scope)
                try:
                    version = state_map.getVersion()
                except:
                    version = "unknown"
                
                current_state_map = state_map.toMap() # Java Map
                
                # Py4J Map access
                count_str = current_state_map.get("count")
                count = int(count_str) if count_str else 0
                
                # 3. Increment
                new_count = count + 1
                
                # 4. Prepare new state dictionary
                updated_state = {"count": str(new_count)}
                
                self.logger.error(f"Attempt {i+1}: Count={count}. Updating to {new_count}. StateMap={state_map}")
                
                # 5. Attempt Replace (Atomic Update)
                # replace(oldStateMap, newStateMap, scope)
                try:
                    # In the Python API wrapper, replace might return None even on success?
                    # Logs showed state incrementing between attempts even when result was None.
                    replace_result = state_manager.replace(state_map, updated_state, scope)
                    
                    # If it returns a boolean (Java), use it. If None (Python wrapper quirk?), assume success if no exception?
                    success = True if replace_result is None else replace_result
                    
                except Exception as e:
                     self.logger.error(f"Replace call raised exception: {str(e)}")
                     success = False

                self.logger.info(f"Replace result: {replace_result} (interpreted as {success})")
                
                if success:
                    return FlowFileTransformResult(
                        relationship="success",
                        attributes={"stateful.counter.value": str(new_count)}
                    )
            
            # If we failed 5 times
            self.logger.error("Failed to update state after 5 attempts due to concurrency.")
            return FlowFileTransformResult(relationship="failure")

        except Exception as e:
            self.logger.error(f"StatefulCounter failed: {str(e)}")
            return FlowFileTransformResult(relationship="failure")
