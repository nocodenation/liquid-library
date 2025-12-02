""" This program transforms a xml file to a json file. """

import xmltodict
import json
import argparse


def convert_xml_to_json(input_filepath, output_filepath):
    with open(input_filepath, "r") as file:
        xml_data_string = file.read().replace("\n", "")

    python_dict = xmltodict.parse(xml_data_string)

    with open(output_filepath, "w") as out_file:
        json.dump(python_dict, out_file)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("input_filepath")
    parser.add_argument("output_filepath")
    args = parser.parse_args()
    convert_xml_to_json(args.input_filepath, args.output_filepath)