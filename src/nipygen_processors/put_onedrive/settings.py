# NAME defines the name of the Python Processor
NAME = "PutOneDrive"
# VERSION defines the version of the Python Processor
VERSION = "0.7.1"
# DESCRIPTION defines the description of the Python Processor
DESCRIPTION = "Uploads files to OneDrive"
# TAGS contains a list of  tags for the Python Processor
TAGS = ["onedrive", "cloud", "storage", "upload"]
# LICENSE defines the license that the Python Processor is published under
LICENSE = "Apache 2.0"
# AUTHORS contains the names of the authors of the Python Processor
AUTHORS = [
    "Christof Dilcher"
]

# Defines the type of the Python Processor. Possible values are: `flow_file_transform`, `flow_file_source`,
PROCESSOR_TYPE = "flow_file_transform"

# If any system libraries are required by the Python Processor, they can be added to the list below
# These will be mapped to the appropriate system libraries for the target platform
# If no system libraries are required, this can be an empty list
SYSTEM_DEPENDENCIES = []

# If any scripts need to be run while setting up the processor, they can be added to the list below.
# Paths are relative to the position of this settings file.
# If no scripts need to be run, this can be an empty list
SYSTEM_SCRIPTS = []

# Any files containing Python requirements for the Python Processor can be added to the list below. Paths are relative
# to the position of this settings file. This list may be empty if no Python dependencies are required.
PYTHON_DEPENDENCIES = [
    "requirements.txt"
]

# TARGET defines the target platform for the Python Processor. Possible values are:
# - `nifi` for the default Nifi container
# - `container`: For the Nifi sidecar extensions container
# - `server`: For the Nifi extension server
TARGET = "nifi"

# TARGET_SYSTEM defines the type of the target system base OS. Possible values are:
# - `alpine`
# - `debian`
# - `ubuntu`
# This is defined by the target configuration but can be overwritten here.
TARGET_SYSTEM = "debian"

# RUN_REMOTELY may be either `True`, `False` or `None`.
# If `True`, the Python Processor will require to be executed in a remote environment.
# If `False`, the Python Processor will require to be executed locally on the Nifi container.
# If `None`, the Python Processor can be executed in either a remote or local environment.
# If the Python Processor requires system libraries, this cannot be set to `False`.
RUN_REMOTELY = None

# Temp file folder path
TEMP_FILE_FOLDER = "/tmp"

# MODULE_CALLABLE_NAME defines the name of the function that will be called by the Python Processor
MODULE_CALLABLE_NAME = "put_onedrive"

# PYTHON_MAIN_MODULE_NAME defines the name of the main Python module that will be called by the Python Processor.
# This is the filename minus the `.py` extension.
PYTHON_MAIN_MODULE_NAME = "put_onedrive"

# PYTHON_MODULE_FOLDER_NAME defines the name of the folder that contains the Python module.
PYTHON_MODULE_FOLDER_NAME = "put_onedrive"

# REQUEST_DATA_FORMAT contains a dictionary that describes the format of the request that the Python Processor
# or None, if the data is directly parsed from the properties.
REQUEST_DATA_FORMAT = None

# PROPERTY_DESCRIPTION contains a list of dictionaries, each of which describes a property that the Python Processor
# requires.
PROPERTY_DESCRIPTION = [
    {
        "name": "tenant_id",
        "display_name": "OneDrive Tenant ID",
        "description": "The tenant ID for OneDrive authentication",
        "type": "str",
        "required": True,
        "sensitive": True,
        "argument_type": "positional",
        "argument_position": 0,
        "expression_language_scope": "NONE"
    },
    {
        "name": "client_id",
        "display_name": "OneDrive Client ID",
        "description": "The client ID for OneDrive authentication",
        "type": "str",
        "required": True,
        "sensitive": True,
        "argument_type": "positional",
        "argument_position": 1,
        "expression_language_scope": "NONE"
    },
    {
        "name": "client_secret",
        "display_name": "OneDrive Client Secret",
        "description": "The client secret for OneDrive authentication",
        "type": "str",
        "required": True,
        "sensitive": True,
        "argument_type": "positional",
        "argument_position": 2,
        "expression_language_scope": "NONE"
    },
    {
        "name": "sharepoint_url",
        "display_name": "SharePoint URL",
        "description": "The SharePoint URL for OneDrive access",
        "type": "str",
        "required": True,
        "argument_type": "positional",
        "argument_position": 3,
        "expression_language_scope": "FLOWFILE_ATTRIBUTES"
    },
    {
        "name": "target_path",
        "display_name": "Target Path",
        "description": "The target path in OneDrive where the file should be uploaded",
        "type": "str",
        "required": True,
        "argument_type": "positional",
        "argument_position": 4,
        "expression_language_scope": "FLOWFILE_ATTRIBUTES"
    },
    {
        "name": "source_path",
        "display_name": "Source Path",
        "description": "The path to the file to upload",
        "type": "str",
        "required": True,
        "argument_type": "positional",
        "argument_position": 5,
        "expression_language_scope": "FLOWFILE_ATTRIBUTES",
        "use_content": True,
        "pass_temp_file": "FILE_PATH",
        "temp_file_id": "input_file"
    },
    {
        "name": "scopes",
        "display_name": "API Scopes",
        "description": "The scopes for OneDrive API access",
        "type": "str",
        "required": False,
        "default_value": "https://graph.microsoft.com/.default",
        "argument_type": "keyword",
        "expression_language_scope": "NONE"
    },
    {
        "name": "create_missing_folders",
        "display_name": "Create Missing Folders",
        "description": "Whether to create missing folders in the target path",
        "type": "bool",
        "required": False,
        "default_value": True,
        "argument_type": "keyword",
        "expression_language_scope": "NONE"
    },
    {
        "name": "onedrive_file_name",
        "display_name": "Target Filename",
        "description": "The filename to use in OneDrive (if different from source)",
        "type": "str",
        "required": False,
        "default_value": None,
        "argument_type": "keyword",
        "expression_language_scope": "FLOWFILE_ATTRIBUTES"
    }
]

# CALCULATED_ARGUMENTS is a list of arguments that are calculated based on other argument values.
CALCULATED_ARGUMENTS = []

def transform_result(result):
    """Transform the result of the processor execution into a dictionary that can be used by the processor framework."""
    if result is None:
        return {
            "status": "failure",
            "error_code": 1,
            "error_message": "Upload failed",
            "url": None
        }
    return {
        "status": "success",
        "error_code": 0,
        "error_message": None,
        "url": result  # result is the OneDrive URL
    }

# Callable for transforming the callable result data to the desired RESULT_DATA_FORMAT
RESULT_DATA_TRANSFORMATION = transform_result

# RESULT_DATA_FORMAT contains a dictionary that describes the format of the result
# The keys of the dictionary are the names of the attributes in the result data.
# If attributes are nested, they can be defined using dot notation.
# Each attribute entry must contain a dictionary with the following keys:
# - `type`: The type of the attribute.
#           Possible values are: `str`, `int`, `float`, `bool`, `bytes`, `list`, `set`, `dict`, `datetime`, `date`.
#           This key is required.
# - `required`: Whether the attribute is required. Possible values are: `True`, `False`.
#               This key is not required, the default value is `False`.
# - `default`: The default value of the attribute. This is only used if the attribute is not required.
#              This key is not required. The default value is `None`.
RESULT_DATA_FORMAT = {
    "status": {
        "type": "str",
        "required": False,
        "default": "success",
    },
    "error_code": {
        "type": "int",
        "required": False,
        "default": None,
    },
    "error_message": {
        "type": "str",
        "required": False,
        "default": None,
    },
    "url": {
        "type": "str",
        "required": False,
        "default": None,
    }
}

# A list of possible result relationships. The relationship name will be used to route the flow file to the
# appropriate relationship.
# The relationship names `original` and `failure` are reserved and should not be listed.
# If a relationship `success` should be used, it must be listed.
# Result data may be routed to `failure` but not to `original`.
# Each relationship should be a dictionary containing two keys: "name" and "description".
# The "name" key should contain the name of the relationship.
# The "description" key should contain a description of the relationship.
RESULT_DATA_RELATIONSHIPS = [
    {
        "name": "success",
        "description": "File was successfully uploaded to OneDrive",
    }
]

# RESULT_DATA_TO_RELATIONSHIP_NAME_TRANSFORMATION points to a callable that retrieves the structure described in
# RESULT_DATA_FORMAT and should return a string with the alotted relationship name.
# If RESULT_DATA_TO_RELATIONSHIP_NAME_TRANSFORMATION is a string, then this string is interpreted as a key path in
# the structure. A method will be generated that splits this path at the `.` character and attempts to retrieve
# the relationship name from the structure. If the lookup fails, then the relationship `success` will be used.
# If RESULT_DATA_TO_RELATIONSHIP_NAME_TRANSFORMATION is None, the relationship `success` will be used.
# Result data may be routed to `failure` but not to `original`.
RESULT_DATA_TO_RELATIONSHIP_NAME_TRANSFORMATION = "status"

# RESULT_DATA_MAPPING contains a nested dictionary that describes how the result data should be mapped to the flow file.
# The root level key is the relationship name. The value is a dictionary that describes how the result data should be
# mapped to the flow file for this specific relationship.
# Below the relationship name is a dictionary that maps flow file content or attributes to result data fields.
# The content is identified using the key "$content" and the attributes are identified using the key "$attributes".
# Each field or field path map is either a dictionary (for only one entry) or  a list of dictionaries (for one or
# more entries) that describe how the field(s) should be mapped.
# Each dictionary must contain at least one key that describes how the field should be mapped.
# The following keys are possible:
# - `$fixed_value_append`: The value is a fixed value that should be used.
#                          This value will be appended to the final value.
# - `$fixed_value_replace`: The value is a fixed value that should be used.
#                           This value will replace the final value.
# - `property.path`: Dot-separated path to the property that should be used.
#                    The value is another dictionary with the following keys:
#                    - `$mode` is required and describes how the value should be used.
#                              Possible values are `append` and `replace`.
#                    - `$default` is optional and describes the default value that should be used if the property is not
#                                 present.
# - `$file_content`: Adds file content. The value is a dictionary with the following keys:
#                    - `$mode` is required and describes how the value should be used.
#                              Possible values are `append` and `replace`.
#                    - `$default` is optional and describes the default value that should be used if the referenced file
#                                 is not present.
#                    - `$file` is a string or a dictionary that describes how the file should be referenced.
#                              A string would contain the hard-coded path to a file.
#                              The following keys are possible:
#                              - `$attribute` is the `name` of the attribute that contains the file path.
#                              - `$fixed_value` is a fixed value that should be used as the file path.
#                              - `$temp_file_id` is the id of the temporary file that should be used.
#                                                This ID needs to be assigned to a property in PROPERTY_DESCRIPTION.
#                    - `$max_size` is optional and describes the maximum size of the file content in bytes.
#                                  Larger files will not be loaded into memory.
#                                  Default is 100MB. Set to -1 to disable file size checks.
#
# Please note: Nifi does not allow updating the content on failure.
# Therefore any content updates of the failure relationship will be offered to Nifi by the processor
# but will most likely not be accepted by Nifi.
RESULT_DATA_MAPPING = {
    "success": {
        "$attributes": {
            "onedrive.upload.success": {"status": {"$mode": "replace", "$default": "false"}},
            "onedrive.upload.url": {"url": {"$mode": "replace", "$default": ""}}
        }
    },
    "failure": {
        "$content": [
            {"error_code": {"$mode": "replace"}},
            {"$fixed_value_append": ": "},
            {"error_message": {"$mode": "append"}},
        ],
        "$attributes": {
            "onedrive.upload.success": {"$fixed_value_replace": "false"},
            "error_message": {"error_message": {"$mode": "replace"}},
            "error_code": {"error_code": {"$mode": "replace"}},
            "onedrive.upload.url": {"$fixed_value_replace": ""}
        }
    }
}
