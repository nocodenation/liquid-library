from nifiapi.properties import PropertyDescriptor, StandardValidators, ExpressionLanguageScope, PropertyDependency
from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
import json
import requests
import ssl

class AttributeToParameter(FlowFileTransform):
    
    class Java:
        implements = ['org.apache.nifi.python.processor.FlowFileTransform']

    class ProcessorDetails:
        version = '0.0.1'
        description = "Updates a Parameter Context parameter with a value derived from FlowFile attributes. " \
                      "Uses the NiFi REST API to perform the update as Python processors cannot natively write to Parameter Contexts."
        tags = ["python", "parameter", "context", "rest", "api"]

    API_URL = PropertyDescriptor(
        name="NiFi API URL",
        description="The base URL for the NiFi API (e.g., http://localhost:8080/nifi-api)",
        required=True,
        validators=[StandardValidators.URL_VALIDATOR]
    )

    AUTH_TOKEN = PropertyDescriptor(
        name="Authentication Token",
        description="Bearer token for authentication if required.",
        required=False,
        sensitive=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    CLIENT_CERT = PropertyDescriptor(
        name="Client Certificate Path",
        description="Path to the Client Certificate (PEM) for mTLS.",
        required=False,
        validators=[StandardValidators.FILE_EXISTS_VALIDATOR]
    )

    CLIENT_KEY = PropertyDescriptor(
        name="Client Key Path",
        description="Path to the Client Key (PEM) for mTLS. Required if Client Certificate is provided.",
        required=False,
        validators=[StandardValidators.FILE_EXISTS_VALIDATOR]
    )

    USERNAME = PropertyDescriptor(
        name="Username",
        description="NiFi Username for token-based authentication.",
        required=False,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    PASSWORD = PropertyDescriptor(
        name="Password",
        description="NiFi Password for token-based authentication.",
        required=False,
        sensitive=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    SSL_CONTEXT_SERVICE = PropertyDescriptor(
        name="SSL Context Service",
        description="SSL Context Service to use for secure communication. "
                    "If provided, Client Certificate/Key properties are ignored. "
                    "Note: Ensure the service uses formats compatible with Python (e.g., PEM) if possible.",
        required=False,
        controller_service_definition="org.apache.nifi.ssl.SSLContextService"
    )

    PARAMETER_CONTEXT = PropertyDescriptor(
        name="Parameter Context Name",
        description="The name of the Parameter Context to update.",
        required=True,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    PARAMETER_NAME = PropertyDescriptor(
        name="Parameter Name",
        description="The name of the parameter to update.",
        required=True,
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )

    PARAMETER_VALUE = PropertyDescriptor(
        name="Parameter Value",
        description="The value to set. Supports Expression Language (e.g., ${my.attribute}).",
        required=True,
        expression_language_scope=ExpressionLanguageScope.FLOWFILE_ATTRIBUTES,
        validators=[StandardValidators.NON_EMPTY_VALIDATOR]
    )
    
    SSL_CONTEXT = PropertyDescriptor(
        name="SSL Context Service",
        description="SSL Context Service to use for secure communication with NiFi API. If not specified and API URL is https, verification might fail or rely on system CA.",
        # Note: In standard Python processors we can't easily inject a Controller Service reference yet in the same way 
        # Java processors do for SSLContextService. We will rely on environment or standard requests verify behavior.
        # Alternatively, users can disable verification via an undocumented property if strictly needed for dev.
        required=False
    )
    
    VERIFY_SSL = PropertyDescriptor(
        name="Verify SSL",
        description="Whether to verify SSL certificates against the CA.",
        required=True,
        default_value="true",
        validators=[StandardValidators.BOOLEAN_VALIDATOR]
    )

    def __init__(self, **kwargs):
        # NiFi passes 'jvm' in kwargs, but parent class might not accept it.
        # We pop it here to prevent TypeError and store it if needed (though not used currently).
        self.jvm = kwargs.pop('jvm', None)
        super().__init__(**kwargs)
        self.property_descriptors = [
            self.API_URL, 
            self.AUTH_TOKEN,
            self.CLIENT_CERT,
            self.CLIENT_KEY,
            self.USERNAME,
            self.PASSWORD, 
            self.SSL_CONTEXT_SERVICE,
            self.PARAMETER_CONTEXT, 
            self.PARAMETER_NAME, 
            self.PARAMETER_VALUE,
            self.VERIFY_SSL
        ]

    def getPropertyDescriptors(self):
        return self.property_descriptors

    def transform(self, context, flowFile):
        api_url = context.getProperty(self.API_URL).getValue().rstrip('/')
        auth_token = context.getProperty(self.AUTH_TOKEN).getValue()
        
        client_cert = context.getProperty(self.CLIENT_CERT).getValue()
        client_key = context.getProperty(self.CLIENT_KEY).getValue()
        username = context.getProperty(self.USERNAME).getValue()
        password = context.getProperty(self.PASSWORD).getValue()
        ssl_service = context.getProperty(self.SSL_CONTEXT_SERVICE).asControllerService()
        
        pc_name = context.getProperty(self.PARAMETER_CONTEXT).getValue()
        param_name = context.getProperty(self.PARAMETER_NAME).evaluateAttributeExpressions(flowFile).getValue()
        param_value = context.getProperty(self.PARAMETER_VALUE).evaluateAttributeExpressions(flowFile).getValue()
        verify_ssl = context.getProperty(self.VERIFY_SSL).asBoolean()
        
        headers = {
            "Content-Type": "application/json"
        }
        
        session = requests.Session()
        session.verify = verify_ssl

        # Configure Authentication
        if auth_token:
            headers["Authorization"] = f"Bearer {auth_token}"
        elif ssl_service:
            # Extract properties from SSL Service
            try:
                keystore_file = ssl_service.getKeyStoreFile()
                keystore_pass = ssl_service.getKeyStorePassword()
                truststore_file = ssl_service.getTrustStoreFile()
                
                # Check for PKCS12
                is_p12 = False
                if keystore_file and (keystore_file.endswith('.p12') or keystore_file.endswith('.pfx')):
                    try:
                        from requests_pkcs12 import Pkcs12Adapter
                        mount_adapter = Pkcs12Adapter(pkcs12_filename=keystore_file, pkcs12_password=keystore_pass)
                        session.mount('https://', mount_adapter)
                        is_p12 = True
                    except ImportError:
                        self.logger.warn("Keystore extension indicates PKCS12 but requests-pkcs12 library is not available. Attempting standard requests cert.")
                
                if not is_p12 and keystore_file:
                    # Requests expects (cert, key) tuple or single file with both (PEM).
                    session.cert = keystore_file 
                
                if truststore_file:
                    session.verify = truststore_file
                    
            except Exception as e:
                return FlowFileTransformResult(relationship="failure", contents=flowFile, attributes={"error.message": f"Failed to use SSL Context Service: {str(e)}"})
                
        elif client_cert and client_key:
             session.cert = (client_cert, client_key)
        elif username and password:
            # Exchange user/pass for token
            try:
                token_url = f"{api_url}/access/token"
                token_resp = session.post(token_url, data={"username": username, "password": password})
                token_resp.raise_for_status()
                # Token endpoint returns text plain token
                generated_token = token_resp.text
                headers["Authorization"] = f"Bearer {generated_token}"
            except Exception as e:
                return FlowFileTransformResult(relationship="failure", contents=flowFile, attributes={"error.message": f"Authentication failed: {str(e)}"})

        try:
            # 1. Find Parameter Context ID and current Version (Revision)
            search_url = f"{api_url}/flow/search-results?q={pc_name}"
            # Note: Search results might be ambiguous if multiple things have the same name, 
            # but Parameter Context names are unique in the scope of the search usually returning the context object.
            # A more robust way is getting all contexts and filtering, but that's heavy.
            # Let's try listing contexts with a filter if available or just list all (lightweight metadata)
            
            # Better approach: List parameter contexts and filter client-side. 
            # /flow/parameter-contexts/ is not a direct endpoint for listing all with name filter.
            # We interact with /flow/process-groups/root/parameter-contexts or similar to list?
            # Actually, standard flow search is often easiest.
            
            # Let's try the direct search approach first.
            search_resp = session.get(search_url, headers=headers)
            search_resp.raise_for_status()
            search_results = search_resp.json()
            
            pc_id = None
            if 'parameterContextResults' in search_results.get('searchResultsDTO', {}):
                for pc in search_results['searchResultsDTO']['parameterContextResults']:
                    if pc['name'] == pc_name:
                        pc_id = pc['id']
                        break
            
            if not pc_id:
                # Fallback: Maybe access via specific known ID? No, user supplied name.
                # Try getting the specific Context if the user provided an ID by mistake? No.
                return FlowFileTransformResult(relationship="failure", contents=flowFile, attributes={"error.message": f"Parameter Context '{pc_name}' not found."})

            # 2. Get current revision and details
            pc_url_base = f"{api_url}/parameter-contexts/{pc_id}"
            get_resp = session.get(pc_url_base, headers=headers)
            get_resp.raise_for_status()
            pc_data = get_resp.json()
            
            current_version = pc_data['revision']['version']
            client_id = pc_data['revision']['clientId']
            
            # 3. Construct Payload
            payload = {
                "revision": {
                    "version": current_version,
                    "clientId": client_id
                },
                "id": pc_id,
                "component": {
                    "id": pc_id,
                    "parameters": [{
                        "parameter": {
                            "name": param_name,
                            "value": param_value
                        }
                    }]
                }
            }

            # 4. Perform Update via Update Request (Async) to handle running components
            # Direct PUT fails if components are running.
            import time
            import random
            
            # Initiate Update Request
            update_req_url = f"{pc_url_base}/update-requests"
            max_retries = 3
            update_request_id = None
            
            for attempt in range(max_retries):
                try:
                    init_resp = session.post(update_req_url, headers=headers, json=payload)
                    init_resp.raise_for_status()
                    update_req_data = init_resp.json()
                    update_request_id = update_req_data['request']['requestId']
                    break
                except requests.exceptions.HTTPError as e:
                    if e.response.status_code == 409 and attempt < max_retries - 1:
                         self.logger.warn(f"Conflict initiating update (409). Retrying {attempt+2}/{max_retries}...")
                         time.sleep(random.uniform(0.1, 0.5))
                         # Must refetch revision
                         get_resp = session.get(pc_url_base, headers=headers)
                         if get_resp.ok:
                             pc_data = get_resp.json()
                             payload['revision']['version'] = pc_data['revision']['version']
                             payload['revision']['clientId'] = pc_data['revision']['clientId']
                         continue
                    raise e
            
            # Poll for completion
            poll_url = f"{update_req_url}/{update_request_id}"
            completed = False
            for _ in range(30): # 30 seconds max wait
                poll_resp = session.get(poll_url, headers=headers)
                poll_resp.raise_for_status()
                poll_data = poll_resp.json()
                if poll_data['request']['complete']:
                    completed = True
                    # Check for failure in the request itself
                    if poll_data['request'].get('failureReason'):
                         raise Exception(f"Update Request failed: {poll_data['request'].get('failureReason')}")
                    break
                time.sleep(1)
            
            if not completed:
                # Cancel/Delete the request if timed out?
                session.delete(poll_url, headers=headers)
                raise Exception("Timed out waiting for Parameter Context update to complete.")
            
            # Acknowledge/Delete Request
            try:
                session.delete(poll_url, headers=headers)
            except:
                pass # Best effort cleanup

            return FlowFileTransformResult(relationship="success", contents=flowFile)

            return FlowFileTransformResult(relationship="success", contents=flowFile)

        except Exception as e:
            self.logger.error(f"Failed to update parameter context: {str(e)}")
            return FlowFileTransformResult(relationship="failure", contents=flowFile, attributes={"error.message": str(e)})

