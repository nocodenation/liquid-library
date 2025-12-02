# NAME defines the name of the Python Processor
NAME = "MergeData"
# VERSION defines the version of the Python Processor
VERSION = "0.1.0"
# DESCRIPTION defines the description of the Python Processor
DESCRIPTION = "Merges a data set into another data set"
# TAGS contains a list of  tags for the Python Processor
TAGS = ["merge", "data", "graphql"]
# LICENSE defines the license that the Python Processor is published under
LICENSE = "Apache 2.0"
# AUTHORS contains the names of the authors of the Python Processor
AUTHORS = [
    "Philipp Freyer",
    "Timur Izmagametov",
    "Dilcher GmbH",
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
SYSTEM_SCRIPTS = ["sample"]

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
# TARGET_SYSTEM = "alpine"

# RUN_REMOTELY may be either `True`, `False` or `None`.
# If `True`, the Python Processor will require to be executed in a remote environment.
# If `False`, the Python Processor will require to be executed locally on the Nifi container.
# If `None`, the Python Processor can be executed in either a remote or local environment.
# If the Python Processor requires system libraries, this cannot be set to `False`.
# This is defined by the target configuration but can be overwritten here.
# RUN_REMOTELY = False

# Temp file folder path
# This is defined by the target configuration but can be overwritten here.
# TEMP_FILE_FOLDER = "/tmp"

# PROPERTY_DESCRIPTION contains a list of dictionaries, each of which describes a property that the Python Processor
# requires. Each dictionary must contain
# - `name`: The name of the property
# - `display_name`: The display name of the property
# - `type`: The type of the property
# - `argument_type`: The type of the argument in the Python Processor. Possible values are: `positional`, `keyword`
# - `argument_position`: The position of the argument in the Python Processor. This is only required if `argument_type`
#                        is `positional`
# - `required`: Whether the property is required or not
# - `default_value`: The default value of the property (if any)
# - `allowed_values`: A list of allowed values for the property (if any)
# - `use_content`: Whether the property uses the flow file content. Defaults to False.
# - `use_fixed_value`: Whether the property is a fixed value. Defaults to False.
# - `fixed_value_value`: The fixed value of the property. Only required if `fixed_value` is True.
# - `pass_temp_file`: Whether the property passes a temporary file, containing the value. Possible values:
#                     - `None`: The property doe snot pass a temporary file
#                     - `"FILE_PATH"`: The property passes the input temporary file path
#                     - `"FILE_POINTER"`: The property passes the input temporary file like object
# - `temp_file_id`: The id of the temporary file that is passed.
#                   Only required if the temp file should be used for the output mapping as well.
#
# Each dictionary may also contain one or more of these values:
# - `expression_language_scope`: The expression language scope.
#                                Must be one of `NONE`, `ENVIRONMENT`, `FLOWFILE_ATTRIBUTES`.
#                                Defaults to `NONE`.
# - `dependencies`: A list of tuples that describe dependencies between properties. Each tuple must contain at least
#                   one values, ... # TODO
#
# If a fixed value is used, the `fixed_value_value` is required. Multiple properties can be defined as fixed values.
# If use content is set to True, the property will use the content of the flow file as the value. A maximum of one
# property can use the content of the flow file.
# If a property is not a fixed value or uses the content of the flow file, it will use the value from the processor
# context.
# If use_fixed_value and use_content are both set to True, the property will use the fixed value.

PROPERTY_DESCRIPTION = [
    {
        "name": "id_key",
        "display_name": "Data Id key",
        "type": "str",
        "required": True,
        "argument_type": "positional",
        "argument_position": 0,
        "expression_language_scope": "FLOWFILE_ATTRIBUTES",
    },
    {
        "name": "input_type",
        "display_name": "Input data type",
        "type": "str",
        "required": True,
        "allowed_values": ["csv", "json"],
        "argument_type": "keyword",
    },
    {
        "name": "input_path",
        "display_name": "Input data path",
        "type": "str",
        "required": True,
        "argument_type": "positional",
        "argument_position": 1,
        "expression_language_scope": "FLOWFILE_ATTRIBUTES",
    },
    {
        "name": "patch_type",
        "display_name": "Patch data type",
        "type": "str",
        "required": True,
        "allowed_values": ["csv", "json"],
        "argument_type": "keyword",
    },
    {
        "name": "patch_path",
        "display_name": "Patch data path",
        "type": "str",
        "required": True,
        "argument_type": "positional",
        "argument_position": 2,
        "expression_language_scope": "FLOWFILE_ATTRIBUTES",
    },
    {
        "name": "output_path",
        "display_name": "Output data path",
        "description": "Output will be the same type than the input",
        "type": "str",
        "required": True,
        "argument_type": "positional",
        "argument_position": 3,
        "expression_language_scope": "FLOWFILE_ATTRIBUTES",
    },
    {
        "name": "input_sub_path",
        "display_name": "Path to the entry list in the Input JSON structure",
        "type": "str",
        "required": False,
        "default": None,
        "argument_type": "keyword",
        "expression_language_scope": "FLOWFILE_ATTRIBUTES",
        "dependencies": [("input_type", "json")],
    },
    {
        "name": "input_csv_args",
        "display_name": "Input CSV arguments",
        "description": """
Comma-separated list of input CSV parser arguments.
Structure: arg=value,arg2=value2,...
Quoting using double quote is enabled.
""",
        "type": "str",
        "required": False,
        "default": "",
        "argument_type": "keyword",
        "expression_language_scope": "FLOWFILE_ATTRIBUTES",
        "dependencies": [("input_type", "csv")],
    },
    {
        "name": "patch_sub_path",
        "display_name": "Path to the entry list in the Patch JSON structure",
        "type": "str",
        "required": False,
        "default": None,
        "argument_type": "keyword",
        "expression_language_scope": "FLOWFILE_ATTRIBUTES",
        "dependencies": [("patch_type", "json")],
    },
    {
        "name": "patch_csv_args",
        "display_name": "Patch CSV arguments",
        "description": """
Comma-separated list of patch CSV parser arguments.
Structure: arg=value,arg2=value2,...
Quoting using double quote is enabled.
""",
        "type": "str",
        "required": False,
        "default": "",
        "argument_type": "keyword",
        "expression_language_scope": "FLOWFILE_ATTRIBUTES",
        "dependencies": [("patch_type", "csv")],
    }
]

# MODULE_CALLABLE_NAME defines the name of the function that will be called by the Python Processor
MODULE_CALLABLE_NAME = "merge_data"

# PYTHON_MAIN_MODULE_NAME defines the name of the main Python module that will be called by the Python Processor.
# This is the filename minus the `.py` extension.
PYTHON_MAIN_MODULE_NAME = "merge_data"

# PYTHON_MODULE_FOLDER_NAME defines the name of the folder that contains the Python module.
PYTHON_MODULE_FOLDER_NAME = "merge_data"

# REQUEST_DATA_FORMAT contains a dictionary that describes the format of the request that the Python Processor
# or None, if the data is directly parsed from the properties.
REQUEST_DATA_FORMAT = None


def transform_result(x):
    return {
        "status": "success" if x is None else "failure",
        "error_code": 0 if x is None else 1,
        "error_message": None if x is None else str(x),
    }


# Callable for transforming the callable result data to the desired RESULT_DATA_FORMAT. This callable will be called
# with the result data as the argument. Can be None, if no transformation is needed.
# The callable return value may be serialized and transported between execution environments.
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
        "description": "The success relationship",
    },
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
        "$content": [
            {"$file_content": {
                "$mode": "replace",
                "$default": "",
                "$file": {
                    "$attribute": "output_path",
                    # "$fixed_value": "/my/output/path/file.json",
                },
                "$max_size": 100000000,
            }},
        ]
    },
    "failure": {
        "$content": [
            {"error_code": {"$mode": "replace"}},
            {"$fixed_value_append": ": "},
            {"error_message": {"$mode": "append"}},
        ],
        "$attributes": {
            "error_message": {"error_message": {"$mode": "replace"}},
            "error_code": {"error_code": {"$mode": "replace"}},
        },
    },
}