import sys
from abc import ABC, abstractmethod
import argparse
import csv
import json
import os
from io import StringIO

TRUTH_VALUE_LIST = {
    "y",
    "yes",
    "true",
    "1",
    "1.0",
    "wahr",
}


def is_true(value):
    """
    Checks whether or not a value may evaluate to True
    :param value: Value to check
    :type value: basestring
    :return: True, if the value is a truth value
    :rtype: bool
    """
    return str(value).strip().lower() in TRUTH_VALUE_LIST


def ensure_directory(local_path):
    if not os.path.exists(local_path):
        try:
            os.makedirs(local_path)
        except OSError:
            # Folder already got created by another thread
            pass


class MergeableData(ABC):
    def __init__(self, file_path, id_key):
        self.id_key = id_key
        self.load_file(file_path)

    @abstractmethod
    def load_file(self, file_path):
        """
        Loads a file into the local object
        :param file_path: file path
        :type file_path: str
        """
        pass

    @property
    @abstractmethod
    def internal_dict(self):
        pass

    def manage_new(self, key, entry):
        pass

    def merge_data(self, update_dict):
        """
        Merges data together
        :param update_dict: Update data
        :type update_dict: Union[Dict, MergeableData]
        """
        if isinstance(update_dict, MergeableData):
            for other_dict in update_dict.internal_dict.values():
                other_key = other_dict.get(self.id_key, None)
                if other_key in self.internal_dict:
                    self.internal_dict[other_key].update(other_dict)
                else:
                    self.internal_dict[other_key] = other_dict
                    self.manage_new(other_key, other_dict)
        elif isinstance(update_dict, dict):
            other_key = update_dict.get(self.id_key, None)
            if other_key in self.internal_dict:
                self.internal_dict[other_key].update(update_dict)
            else:
                self.internal_dict[other_key] = update_dict
                self.manage_new(other_key, update_dict)

    @abstractmethod
    def save_file(self, file_path):
        """
        Saves the current data into a file path
        :param file_path: file path
        :type file_path: str
        """
        pass


# CSV Data class
class MergeableCSVData(MergeableData):

    def __init__(self, file_path, id_key, **csv_args):
        self.data = {}
        self.csv_args = csv_args
        super().__init__(file_path, id_key)

    @property
    def newline(self):
        return self.csv_args["newline"] if "newline" in self.csv_args else None

    @property
    def encoding(self):
        return self.csv_args["encoding"] if "encoding" in self.csv_args else None

    @property
    def _csv_args(self):
        allowed_keys = {
            "dialect",
            "delimiter",
            "quotechar",
            "quoting",
            "doublequote",
            "lineterminator",
            "skipinitialspace",
            "strict",
        }
        clean_dict = {
            str(key).lower(): value
            for key, value in self.csv_args.items()
            if str(key).lower() in allowed_keys
        }
        quoting_options = {
            "minimal": csv.QUOTE_MINIMAL,
            "all": csv.QUOTE_ALL,
            "none": csv.QUOTE_NONE,
            "nonnumeric": csv.QUOTE_NONNUMERIC,
        }
        if "quoting" in clean_dict:
            if str(clean_dict["quoting"]).lower() in quoting_options:
                clean_dict["quoting"] = quoting_options[str(clean_dict["quoting"]).lower()]
            else:
                del clean_dict["quoting"]
        if "doublequote" in clean_dict:
            clean_dict["doublequote"] = is_true(clean_dict["doublequote"])
        if "dialect" in clean_dict:
            dialect = str(clean_dict["dialect"]).lower()
            if dialect in frozenset(csv.list_dialects()):
                clean_dict["dialect"] = dialect
            else:
                del clean_dict["dialect"]
        if "skipinitialspace" in clean_dict:
            clean_dict["skipinitialspace"] = is_true(clean_dict["skipinitialspace"])
        if "strict" in clean_dict:
            clean_dict["strict"] = is_true(clean_dict["strict"])
        if "lineterminator" in clean_dict:
            clean_dict["lineterminator"] = str(
                clean_dict["lineterminator"]
            ).lower().replace("cr", "\r").replace("lf", "\n")
        return clean_dict

    def infer_field_names(self):
        key_set = set()
        for entry in self.data.values():
            key_set.update(entry.keys())
        return sorted(list(key_set))

    def save_file(self, file_path):
        field_names = self.infer_field_names()
        ensure_directory(os.path.dirname(file_path))
        with open(file_path, "w", newline=self.newline, encoding=self.encoding) as csv_file:
            writer = csv.DictWriter(csv_file, fieldnames=field_names, **self._csv_args)
            writer.writeheader()
            writer.writerows(self.data.values())

    def load_file(self, file_path):
        with open(file_path, "r", newline=self.newline, encoding=self.encoding) as csv_file:
            reader = csv.DictReader(csv_file, **self._csv_args)
            for row in reader:
                self.data[row.get(self.id_key, None)] = {
                    str(key).strip(): str(value).strip() for key, value in row.items()
                }

    @property
    def internal_dict(self):
        return self.data


class ParserError(Exception):
    def __init__(self, message):
        self.message = message


# JSON Data class
class MergeableJSONData(MergeableData):

    def __init__(self, file_path, id_key, json_path=None):
        self.json_path = json_path
        self.json_base = None
        self.json_data = None
        self.data_list = None
        super().__init__(file_path, id_key)

    def load_file(self, file_path):
        self.json_base = json.load(open(file_path, "rb"))
        entry_structure = self.json_base
        if self.json_path is not None:
            keys = self.json_path.split(".")
            for key in keys:
                if isinstance(entry_structure, dict):
                    entry_structure = entry_structure.get(key, {})
                elif isinstance(entry_structure, list):
                    try:
                        key = int(key)
                    except ValueError:
                        raise ParserError(f"path {self.json_path} does not fit the delivered structure")
                    if len(list) > key:
                        entry_structure = entry_structure[key]
                    else:
                        raise ParserError(f"Index error: the delivered structure does not contain an index {key}")
        if isinstance(entry_structure, list):
            self.json_data = {}
            self.data_list = entry_structure
            for entry in entry_structure:
                self.json_data[entry.get(self.id_key, None)] = entry
        elif isinstance(entry_structure, dict):
            self.json_data = entry_structure
        else:
            raise ParserError(
                f"Could not parse the JSON file {file_path} - it neither contains a list, nor a JSON object."
            )

    def manage_new(self, key, entry):
        if self.data_list:
            self.data_list.append(entry)

    @property
    def internal_dict(self):
        return self.json_data

    def save_file(self, file_path):
        ensure_directory(os.path.dirname(file_path))
        json.dump(self.json_base, open(file_path, "w"))


def parse_csv_args(csv_args):
    parsed_list = list(csv.reader(StringIO(csv_args), delimiter=",", quoting=csv.QUOTE_MINIMAL))
    if len(parsed_list) > 0:
        split_args = [e.split("=", 1) for e in parsed_list[0] if e and "=" in e]
        return {arg: value for arg, value in split_args}
    return {}


def merge_data(
        id_key,
        input_path,
        patch_path,
        output_path,
        input_type="csv",
        patch_type="csv",
        input_sub_path=None,
        patch_sub_path=None,
        input_csv_args="",
        patch_csv_args="",
):
    if not os.path.isfile(input_path):
        return f"The provided path '{input_path}' does not exist!"
    if not os.path.isfile(patch_path):
        return f"The provided path '{patch_path}' does not exist!"

    try:
        if input_type == "json":
            input_handler = MergeableJSONData(input_path, id_key, json_path=input_sub_path)
        else:
            parsed_csv_args = parse_csv_args(input_csv_args)
            input_handler = MergeableCSVData(input_path, id_key, **parsed_csv_args)

        if patch_type == "json":
            patch_handler = MergeableJSONData(patch_path, id_key, json_path=patch_sub_path)
        else:
            parsed_csv_args = parse_csv_args(patch_csv_args)
            patch_handler = MergeableCSVData(patch_path, id_key, **parsed_csv_args)

        input_handler.merge_data(patch_handler)
        input_handler.save_file(output_path)
    except ParserError as pe:
        return pe.message
    return None


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="""
        Merges a data set into another data set
        """
    )
    parser.add_argument("id_key", help="Data ID key")
    parser.add_argument("input_type", help="Input data type", choices=("csv", "json"))
    parser.add_argument("input_path", help="Input data path")
    parser.add_argument("patch_type", help="Patch data type", choices=("csv", "json"))
    parser.add_argument("patch_path", help="Patch data path")
    parser.add_argument("output_path", help="Output data path. Output will be the same type than the input")
    parser.add_argument(
        "--input_sub_path",
        "-i",
        default=None,
        help="Path to the entry list in the Input JSON structure",
    )
    parser.add_argument(
        "--input_csv_args",
        "-a",
        default="",
        help="""
Comma-separated list of input CSV parser arguments.
Structure: arg=value,arg2=value2,...
Quoting using double quote is enabled.
""",
    )
    parser.add_argument(
        "--patch_sub_path",
        "-p",
        default=None,
        help="Path to the entry list in the Patch JSON structure",
    )
    parser.add_argument(
        "--patch_csv_args",
        "-b",
        default="",
        help="""
Comma-separated list of patch CSV parser arguments.
Structure: arg=value,arg2=value2,...
Quoting using double quote is enabled.
""",
    )
    args = parser.parse_args()

    result = merge_data(
        args.id_key,
        args.input_path,
        args.patch_path,
        args.output_path,
        input_type=args.input_type,
        patch_type=args.patch_type,
        input_sub_path=args.input_sub_path,
        patch_sub_path=args.patch_sub_path,
        input_csv_args=args.input_csv_args,
        patch_csv_args=args.patch_csv_args,
    )
    if result:
        print(result, file=sys.stderr)
        sys.exit(1)
