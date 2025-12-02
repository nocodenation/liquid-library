#!/usr/bin/env python3

import argparse
import requests
import os.path
import sys
from urllib.parse import unquote
import re

# Custom exceptions for OneDrive operations
# Error codes for different failure scenarios
ERROR_ACCESS_TOKEN = 10
ERROR_DRIVE_ID = 20
ERROR_FOLDER_PATH = 30
ERROR_CREATE_FOLDER = 40
ERROR_UPLOAD_FILE = 50
ERROR_GET_WEB_URL = 60
ERROR_FILE_NOT_FOUND = 70

class OneDriveError(Exception):
    """Base class for OneDrive exceptions"""

    base_description = ""

    def __init__(self, message, error_code):
        self.message = message
        self.error_code = error_code
        super().__init__(self.message)

    def full_message(self):
        return f"{self.base_description}: {self.message}"

class AccessTokenError(OneDriveError):
    """Raised when there's an error getting the access token"""

    base_description = "Failed to get access token"

    def __init__(self, message):
        super().__init__(message, ERROR_ACCESS_TOKEN)

class DriveIdError(OneDriveError):
    """Raised when there's an error getting the drive ID"""

    base_description = "Failed to get drive ID"

    def __init__(self, message):
        super().__init__(message, ERROR_DRIVE_ID)

class FolderPathError(OneDriveError):
    """Raised when there's an error with the folder path"""

    base_description = "Invalid folder path"

    def __init__(self, message):
        super().__init__(message, ERROR_FOLDER_PATH)

class CannotCreateFolder(OneDriveError):
    """Raised when a folder cannot be created in OneDrive"""

    base_description = "Failed to create folder"

    def __init__(self, message):
        super().__init__(message, ERROR_CREATE_FOLDER)

class CannotUploadFile(OneDriveError):
    """Raised when a file cannot be uploaded to OneDrive"""

    base_description = "Failed to upload file"

    def __init__(self, message):
        super().__init__(message, ERROR_UPLOAD_FILE)

class WebUrlError(OneDriveError):
    """Raised when there's an error getting the web URL"""

    base_description = "Failed to get web URL"

    def __init__(self, message):
        super().__init__(message, ERROR_GET_WEB_URL)

class FileNotFoundError(OneDriveError):
    """Raised when the source file is not found"""

    base_description = "Source file not found"

    def __init__(self, message):
        super().__init__(message, ERROR_FILE_NOT_FOUND)

def get_access_token(tenant_id, client_id, client_secret, scopes):
    """Get access token from Microsoft Graph API."""
    try:
        token_url = f"https://login.microsoftonline.com/{tenant_id}/oauth2/v2.0/token"
        
        data = {
            'grant_type': 'client_credentials',
            'client_id': client_id,
            'client_secret': client_secret,
            'scope': scopes
        }
        
        response = requests.post(token_url, data=data)
        
        if response.status_code != 200:
            raise AccessTokenError(f"Failed to get access token: {response.text}")
            
        access_token = response.json().get('access_token')
        if not access_token:
            raise AccessTokenError("Access token not found in response")
            
        return access_token
    except requests.exceptions.RequestException as e:
        raise AccessTokenError(f"Network error while getting access token: {str(e)}")
    except Exception as e:
        if not isinstance(e, OneDriveError):
            raise AccessTokenError(f"Unexpected error getting access token: {str(e)}")
        raise

def get_drive_id(access_token, sharepoint_url):
    """Get the drive ID and user ID (for personal OneDrive) from the SharePoint site."""
    try:
        # Decode the URL first
        decoded_url = unquote(sharepoint_url)
        
        # For personal OneDrive (URLs containing '-my.sharepoint.com')
        if '-my.sharepoint.com' in decoded_url:
            headers = {
                'Authorization': f'Bearer {access_token}',
                'Content-Type': 'application/json'
            }
            
            # Extract user information from the URL
            url_parts = decoded_url.split('://')[-1].split('/')
            if 'personal' not in url_parts:
                raise DriveIdError("Could not find 'personal' in SharePoint URL")
                
            personal_index = url_parts.index('personal')
            if personal_index + 1 >= len(url_parts):
                raise DriveIdError("Could not find user ID in SharePoint URL")
                
            user_id = url_parts[personal_index + 1]
            # Convert user_id from URL format back to email format
            user_id = user_id.replace('_', '@', 1).replace('_', '.')
            
            # Get the drive using the user's ID
            drive_url = f"https://graph.microsoft.com/v1.0/users/{user_id}/drive"
            response = requests.get(drive_url, headers=headers)
            
            if response.status_code != 200:
                raise DriveIdError(f"Error getting drive ID: {response.text}")
            
            drive_id = response.json().get('id')
            if not drive_id:
                raise DriveIdError("Drive ID not found in response")
                
            return drive_id, user_id
        else:
            # For SharePoint sites
            url_parts = decoded_url.split('://')[-1].split('/')
            hostname = url_parts[0]
            site_path = '/'.join(url_parts[1:])
            
            # Construct the site ID
            site_id = f"{hostname}:/{site_path}"
            
            # Get the drive ID
            headers = {
                'Authorization': f'Bearer {access_token}',
                'Content-Type': 'application/json'
            }
            
            drive_url = f"https://graph.microsoft.com/v1.0/sites/{site_id}/drive"
            response = requests.get(drive_url, headers=headers)
            
            if response.status_code != 200:
                raise DriveIdError(f"Error getting drive ID: {response.text}")
            
            drive_id = response.json().get('id')
            if not drive_id:
                raise DriveIdError("Drive ID not found in response")
                
            return drive_id, None
    except requests.exceptions.RequestException as e:
        raise DriveIdError(f"Network error while getting drive ID: {str(e)}")
    except Exception as e:
        if not isinstance(e, OneDriveError):
            raise DriveIdError(f"Unexpected error getting drive ID: {str(e)}")
        raise

def get_full_folder_path(sharepoint_url, onedrive_folder_path):
    """Get the full folder path by combining SharePoint URL path and OneDrive folder path."""
    try:
        if not onedrive_folder_path.startswith('./') and not onedrive_folder_path.startswith('/'):
            raise FolderPathError("OneDrive folder path must start with './' for relative paths or '/' for absolute paths")

        if onedrive_folder_path.startswith('/'):
            # For absolute paths, just remove the leading slash
            return onedrive_folder_path[1:]

        # Handle relative paths starting with ./
        # First decode the URL
        decoded_url = unquote(sharepoint_url)
        
        # For URLs with encoded paths in query parameters
        if 'id=' in decoded_url:
            # Extract the encoded path from the id parameter
            query_parts = decoded_url.split('?')[1].split('&')
            for part in query_parts:
                if part.startswith('id='):
                    encoded_path = part.split('=')[1]
                    # Decode the path
                    path_str = unquote(encoded_path)
                    # Find Documents and extract everything up to the first &
                    if 'Documents' not in path_str:
                        raise FolderPathError("Could not find 'Documents' in SharePoint URL query parameter")
                    
                    path_parts = path_str.split('Documents/')[1].split('&')[0]
                    # Remove any trailing slashes
                    base_path = path_parts.rstrip('/')
                    # Combine with the relative path (removing ./)
                    return f"{base_path}/{onedrive_folder_path[2:]}"
            raise FolderPathError("Could not find valid path in SharePoint URL query parameter")
        else:
            # Regular URL processing
            if 'Documents' not in decoded_url:
                raise FolderPathError("Could not find 'Documents' in SharePoint URL")
                
            # Extract everything between Documents/ and the first ? or #
            base_url = decoded_url.split('Documents/')[1]
            base_path = base_url.split('?')[0].split('#')[0].rstrip('/')
            return f"{base_path}/{onedrive_folder_path[2:]}"
            
    except FolderPathError:
        raise
    except Exception as e:
        raise FolderPathError(f"Error processing folder path: {str(e)}")

def verify_folder_path(access_token, drive_id, folder_path, create_missing=False, user_id=None):
    """Verify that the folder path exists in OneDrive."""
    try:
        if create_missing:
            return create_folder_path(access_token, drive_id, folder_path, user_id)

        headers = {
            'Authorization': f'Bearer {access_token}',
            'Content-Type': 'application/json'
        }

        if user_id:
            folder_url = f"https://graph.microsoft.com/v1.0/users/{user_id}/drive/items/root:{folder_path}"
        else:
            folder_url = f"https://graph.microsoft.com/v1.0/drives/{drive_id}/items/root:{folder_path}"
            
        try:
            response = requests.get(folder_url, headers=headers)
            
            if response.status_code != 200:
                raise FolderPathError(f"Folder path '{folder_path}' does not exist")
                
            folder_info = response.json()
            if not folder_info.get('folder'):
                raise FolderPathError(f"Path '{folder_path}' exists but is not a folder")
                
            return True
            
        except requests.exceptions.RequestException as e:
            raise FolderPathError(f"Network error while verifying folder path: {str(e)}")
            
    except OneDriveError:
        raise
    except Exception as e:
        raise FolderPathError(f"Unexpected error verifying folder path: {str(e)}")

def create_folder_path(access_token, drive_id, folder_path, user_id=None):
    """Create folder path in OneDrive if it doesn't exist."""
    try:
        headers = {
            'Authorization': f'Bearer {access_token}',
            'Content-Type': 'application/json'
        }

        # Split the path into parts
        parts = [p for p in folder_path.split('/') if p]
        current_path = ''

        for part in parts:
            # Update current path
            current_path = f"{current_path}/{part}" if current_path else part

            # Check if folder exists
            if user_id:
                check_url = f"https://graph.microsoft.com/v1.0/users/{user_id}/drive/items/root:{current_path}"
            else:
                check_url = f"https://graph.microsoft.com/v1.0/drives/{drive_id}/items/root:{current_path}"
            response = requests.get(check_url, headers=headers)

            if response.status_code == 404:
                # Folder doesn't exist, create it
                if user_id:
                    parent_path = current_path.rsplit('/', 1)[0] if '/' in current_path else ''
                    if parent_path:
                        create_url = f"https://graph.microsoft.com/v1.0/users/{user_id}/drive/items/root:{parent_path}:/children"
                    else:
                        create_url = f"https://graph.microsoft.com/v1.0/users/{user_id}/drive/items/root/children"
                else:
                    parent_path = current_path.rsplit('/', 1)[0] if '/' in current_path else ''
                    if parent_path:
                        create_url = f"https://graph.microsoft.com/v1.0/drives/{drive_id}/items/root:{parent_path}:/children"
                    else:
                        create_url = f"https://graph.microsoft.com/v1.0/drives/{drive_id}/items/root/children"
                
                folder_data = {
                    "name": part,
                    "folder": {},
                    "@microsoft.graph.conflictBehavior": "replace"
                }
                
                create_response = requests.post(create_url, headers=headers, json=folder_data)
                
                if create_response.status_code not in [201, 200]:
                    # If folder already exists, that's fine, continue
                    if 'nameAlreadyExists' in create_response.text:
                        continue
                    raise CannotCreateFolder(f"Error creating folder '{part}': {create_response.text}")
            elif response.status_code != 200:
                raise CannotCreateFolder(f"Error checking folder '{part}': {response.text}")
            else:
                # Folder exists, verify it's actually a folder
                folder_info = response.json()
                if not folder_info.get('folder'):
                    raise CannotCreateFolder(f"Path component '{part}' exists but is not a folder")

        return True
    except requests.exceptions.RequestException as e:
        raise CannotCreateFolder(f"Network error while creating folder path: {str(e)}")
    except Exception as e:
        if not isinstance(e, OneDriveError):
            raise CannotCreateFolder(f"Unexpected error creating folder path: {str(e)}")
        raise

def upload_file(access_token, drive_id, folder_path, source_content, file_name, user_id=None):
    """Upload a file to OneDrive."""
    try:
        # First, create an upload session
        headers = {
            'Authorization': f'Bearer {access_token}',
            'Content-Type': 'application/json'
        }
        
        # Construct the upload session URL
        # Make sure folder_path starts with a forward slash
        full_path = f"/{folder_path}" if not folder_path.startswith('/') else folder_path
        
        if user_id:
            upload_url = f"https://graph.microsoft.com/v1.0/users/{user_id}/drive/items/root:{full_path}/{file_name}:/createUploadSession"
        else:
            upload_url = f"https://graph.microsoft.com/v1.0/drives/{drive_id}/items/root:{full_path}/{file_name}:/createUploadSession"
        
        # Create upload session
        try:
            session_response = requests.post(upload_url, headers=headers)
            
            if session_response.status_code != 200:
                raise CannotUploadFile(f"Failed to create upload session: {session_response.text}")
            
            # Get the upload URL from the session
            upload_url = session_response.json().get('uploadUrl')
            if not upload_url:
                raise CannotUploadFile("Upload URL not found in session response")
        except requests.exceptions.RequestException as e:
            raise CannotUploadFile(f"Network error while creating upload session: {str(e)}")
        
        # Upload the file content
        content_length = len(source_content)
        
        # For small files (< 4MB), upload in a single request
        headers = {
            'Content-Length': str(content_length),
            'Content-Range': f'bytes 0-{content_length-1}/{content_length}'
        }
        
        upload_response = requests.put(upload_url, headers=headers, data=source_content)
        
        if upload_response.status_code not in [200, 201, 202]:
            raise CannotUploadFile(f"Failed to upload file: {upload_response.text}")
        
        # Get the file web URL
        web_url = get_file_web_url(access_token, drive_id, folder_path, file_name, user_id)
        if not web_url:
            raise WebUrlError("Failed to get web URL for uploaded file")
            
        return web_url
        
    except requests.exceptions.RequestException as e:
        raise CannotUploadFile(f"Network error while uploading file: {str(e)}")
    except Exception as e:
        if not isinstance(e, OneDriveError):
            raise CannotUploadFile(f"Unexpected error uploading file: {str(e)}")
        raise

def get_file_web_url(access_token, drive_id, folder_path, file_name, user_id=None):
    """Get the web URL for viewing the file in OneDrive."""
    try:
        headers = {
            'Authorization': f'Bearer {access_token}',
            'Content-Type': 'application/json'
        }
        
        # Make sure folder_path starts with a forward slash
        path_with_slash = f"/{folder_path}" if not folder_path.startswith('/') else folder_path
        full_path = f"{path_with_slash}/{file_name}"
        
        # Get file information including webUrl
        if user_id:
            file_url = f"https://graph.microsoft.com/v1.0/users/{user_id}/drive/items/root:{full_path}"
        else:
            file_url = f"https://graph.microsoft.com/v1.0/drives/{drive_id}/items/root:{full_path}"
        response = requests.get(file_url, headers=headers)
        
        if response.status_code != 200:
            raise WebUrlError(f"Error getting file web URL: {response.text}")
            
        web_url = response.json().get('webUrl')
        if not web_url:
            raise WebUrlError("Web URL not found in response")
            
        return web_url
    except requests.exceptions.RequestException as e:
        raise WebUrlError(f"Network error while getting web URL: {str(e)}")
    except Exception as e:
        if not isinstance(e, OneDriveError):
            raise WebUrlError(f"Unexpected error getting web URL: {str(e)}")
        raise

def put_onedrive(tenant_id, client_id, secret_key, sharepoint_url, onedrive_folder_path,
                 source_file_path, scopes='https://graph.microsoft.com/.default',
                 create_missing_folders=True, onedrive_file_name=None):
    """Upload content to OneDrive and return its web URL."""
    try:
        # Read the file content
        if not os.path.exists(source_file_path):
            raise FileNotFoundError(f"Source file not found: {file_path}")
            
        source_file_name = os.path.basename(source_file_path)

        with open(source_file_path, 'rb') as f:
            source_content = f.read()
            
        # Get access token
        access_token = get_access_token(tenant_id, client_id, secret_key, scopes)
        
        # Get drive ID and user ID (for personal OneDrive)
        drive_id, user_id = get_drive_id(access_token, sharepoint_url)
        
        # Get the full folder path
        full_folder_path = get_full_folder_path(sharepoint_url, onedrive_folder_path)
        
        # Verify/create the folder path
        if not verify_folder_path(access_token, drive_id, full_folder_path, create_missing_folders, user_id):
            raise CannotCreateFolder(f"Failed to create or verify folder path: {full_folder_path}")
        
        # Use onedrive_file_name if provided, otherwise use source_file_name
        file_name = onedrive_file_name if onedrive_file_name else source_file_name
        
        # Upload the file
        return upload_file(access_token, drive_id, full_folder_path, source_content, file_name, user_id)
        
    except OneDriveError:
        raise
    except Exception as e:
        raise CannotUploadFile(f"Unexpected error uploading file: {str(e)}")

def main():
    parser = argparse.ArgumentParser(description='Upload a file to Microsoft OneDrive')
    
    parser.add_argument('--scopes', 
                      default='https://graph.microsoft.com/.default',
                      help='Comma-separated list of Microsoft token scopes')
    parser.add_argument('--tenant-id', 
                      required=True,
                      help='Microsoft tenant identifier')
    parser.add_argument('--client-id', 
                      required=True,
                      help='Microsoft application client identifier')
    parser.add_argument('--secret-key', 
                      required=True,
                      help='Microsoft application secret key')
    parser.add_argument('--onedrive-folder-path', 
                      required=True,
                      help='''Path to the folder in OneDrive where the file will be uploaded. Can be specified in two ways:
                      1. As a relative path from the SharePoint Start URL (must start with ./):
                         Example: ./subfolder/reports
                      2. As a full path from the OneDrive root (must start with /):
                         Example: /department/shared/reports''')
    parser.add_argument('--sharepoint-url', 
                      required=True,
                      help='''The browser URL of the entry point for file delivery to OneDrive. 
                      This URL serves two purposes:
                      1. It contains the necessary information to identify the correct OneDrive location
                      2. When using a relative OneDrive folder path (starting with ./), this URL's path is used as the base path''')
    parser.add_argument('--source-folder-path',
                      required=True,
                      help='Path to the folder containing the file to upload')
    parser.add_argument('--source-file-name',
                      required=True,
                      help='Name of the source file to upload')
    parser.add_argument('--onedrive-file-name',
                      help='Name of the file as it will appear in OneDrive. If not specified, uses the source file name')
    parser.add_argument('--create-missing-folders',
                      choices=['yes', 'no'],
                      default='yes',
                      help='Create missing folders in the OneDrive path (default: yes)')
    
    args = parser.parse_args()
    
    try:
        web_url = put_onedrive(
            args.tenant_id,
            args.client_id,
            args.secret_key,
            args.sharepoint_url,
            args.onedrive_folder_path,
            os.path.join(args.source_folder_path, args.source_file_name),
            scopes=args.scopes,
            create_missing_folders=args.create_missing_folders == 'yes',
            onedrive_file_name=args.onedrive_file_name
        )
        
        if web_url:
            print(f"File can be accessed at: {web_url}")
            sys.exit(0)
        else:
            print("Failed to get web URL for uploaded file")
            sys.exit(2)  # Using 2 for web URL specific errors
            
    except OneDriveError as e:
        print(e.full_message())
        sys.exit(e.error_code)
    except Exception as e:
        print(f"Unexpected error: {str(e)}")
        sys.exit(1)

if __name__ == "__main__":
    main()
