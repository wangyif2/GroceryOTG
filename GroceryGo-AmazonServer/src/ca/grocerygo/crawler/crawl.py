# This script crawls the following grocery_table store flyer websites:
# 1. Metro
# 2. Loblaws
# 3. Food Basics
# 4. No Frills
# 5. Sobeys
# 6. FreshCo
#
# Requirements:
# - Python 2.x (the MySQLdb module has not been updated to support Python 3.x yet)
# - BeautifulSoup (for HTML parsing)
# - nltk (for NLP)
# - MySQLdb (for connecting to mysql)

from bs4 import BeautifulSoup
from datetime import date
from dateutil import parser
from urlparse import urlparse
import HTMLParser
import MySQLdb as mdb
import ast
import classifier
import cookielib
import datetime
import getNouns
import json
import logging
import nltk
import os
import re
import smtplib
import sys
import time
import traceback
import urllib
import urllib2


# Initialize log file (filename based on YYYY_MM_DD_hhmmsscc.log)
timestamp = datetime.datetime.now()
logname = "./log/" + str(timestamp.year).zfill(4) + "_" + str(timestamp.month).zfill(2) + "_" + str(timestamp.day).zfill(2) + "_" + \
          str(timestamp.hour).zfill(2) + str(timestamp.minute).zfill(2) + str(timestamp.second).zfill(2) + "_" + \
          str(timestamp.microsecond) + ".log"
print("writing log to %s..." % logname)

# Define logging level (if you set this to logging.DEBUG, the debug print messages will be displayed)
logging.basicConfig(filename=logname, format='%(asctime)s:%(levelname)s: %(message)s', level=logging.INFO)

# for debugging
#logging.basicConfig(format='%(asctime)s:%(levelname)s: %(message)s', level=logging.DEBUG)


# Keep these BELOW the logging setup. Otherwise, their loggers get registered as the root.


# Start timing how long it takes to run the whole script
start_timer = time.time()

# Fill in your MySQL user & password
mysql_endpoint = "aa120uk48qkqk9n.crj9vk2lkxxd.us-east-1.rds.amazonaws.com"
mysql_user = "grocerygo"
mysql_password = "GGbmw2013"
mysql_db = "ebdb"

# DEV database endpoint
#mysql_endpoint = "aasn6zu0hiyyyt.cr7ylum4bwiu.us-east-1.rds.amazonaws.com"
#mysql_user = "grocerygo"
#mysql_password = "GGbmw2013"
#mysql_db = "ebdb"

# TODO:
# Done. 1) Pass in only the item part of the line string to getNouns, so it doesn't get confused with the price 
# Done. 2) Build a language model of bigram probabilities to detect compound nouns (e.g. "potato chips" vs just "chips")
#          If a probability of word B to occur after word A is > 0.5, then it's a compound. 
# Done. 3) Add a "Misc" subcategory in database, in case no subcategories match the line.
# Done. 4) Add a "tags" column in Subcategory table in database (use that list of tags instead of the subcategory name)
#          That way, we can exclude words like "and" and improve efficiency.
# Done. 5) Use all words in the list of tags when determining subcategory_id in classifier.py
#


# When getting a table's primary key from MySQL, this is the index of the primary key column name
SQL_INDEX_PRIMARY_KEY = 4

# Column indices in the Item and Grocery tables (used when building the data to insert into the tables)
ITEM_INDEX_RAW_ITEM = 0
GROCERY_INDEX_ITEM_ID = 0
GROCERY_INDEX_RAW_ITEM = 1
GROCERY_INDEX_ORIG_PRICE = 2


def db_escape(s):
    if type(s) == str:
        return mdb.escape_string(s)
    return s

def unescape(s):
    h = HTMLParser.HTMLParser()
    if s:
        s = h.unescape(s)
    return s

def sendNotification(error_msg):
    # Set up mail authentication
    email_username = "grocerygo.ca@gmail.com"
    email_password = "GGbmw2013"
    
    # Set up message
    ts = time.time()
    timestamp = datetime.datetime.fromtimestamp(ts).strftime('%Y-%m-%d %H:%M:%S')
    email_subject = "Server Notification - Crawler Fail"
    email_sender = "grocerygo.ca@gmail.com"
    email_receiver = "grocerygo.ca@gmail.com"
    email_body = "Hi Team, \r\n\r\nThe crawler failed at [" + str(timestamp) +\
        "] with the following output:\r\n\r\n" + str(error_msg)
    
    msg = "\r\n".join([
      "From: " + email_sender,
      "To: " + email_receiver,
      "Subject: " + email_subject,
      "",
      email_body
      ])
    
    # Send the message via Gmail's SMTP server
    server = smtplib.SMTP("smtp.gmail.com", 587)
    server.ehlo()
    server.starttls()
    server.login(email_username, email_password)
    server.sendmail(email_sender, [email_receiver], msg)
    server.quit()


def getFlyer():
    '''No input parameters, accesses the database directly. 
       Finds this week's URL of the accessible plain-text flyer web pages for each grocery_table store
       in the database. Parses the accessible plain-text only flyer webpages to identify items. 
       Return a dictionary of {flyer_id : item} pairs, where "items" is a list of 
       [item_raw_string, unit_price, unit_type_id, total_price, start_date, end_date, page_number, 
       update_date].'''
    flyers = {}
    items = {}
    
    today = datetime.datetime.now()
    update_date = today.strftime("%Y-%m-%d %H:%M:%S")
    
    # Get unit IDs from the database
    cur.execute('SELECT unit_id, unit_type_name FROM Unit;')
    units = cur.fetchall()

    # Fetch all flyer URLs
    cur.execute('SELECT StoreParent.store_parent_id, Flyer.flyer_url, Flyer.flyer_id FROM (Flyer INNER JOIN StoreParent ON StoreParent.store_parent_id=Flyer.store_parent_id) ORDER BY StoreParent.store_parent_name')
    data = cur.fetchall()
    desiredStores = [1,2,3,4,5,6]
    for record in data:
        store_id, next_url, flyer_id = record[0], record[1], record[2]
        flyer_url = ""
        if next_url:
            #logging.info("next url: %s" % next_url)

            if store_id not in desiredStores:
                continue

            # Metro
            if store_id == 1:
                try:
                    flyer_url = ""
                    logging.info("Crawling store: %d (url: %s)" % (store_id, next_url))
                    
                    next_url = "http://www.metro.ca/flyer/index.en.html"
                    hostname = urlparse(next_url).hostname
                    soup = str(BeautifulSoup(urllib2.urlopen(next_url)))
                    
                    start_token = "TINK.bootstrap.jsonPromotions = "
                    end_token = "];"
                    index_start = soup.find(start_token)
                    index_end = soup.find(end_token, index_start)
                    json_input = soup[index_start+len(start_token):index_end+len(end_token)-1]
                    j = json.loads(json_input)
                    store_items = []
                    line_number = 1
                    for row in j:
                        #print(row)
                        start_date = str(parser.parse(row['promotionEndDate']))
                        end_date = str(parser.parse(row['promotionStartDate']))
                        start_date_obj = datetime.datetime.strptime(start_date, "%Y-%m-%d %H:%M:%S")
                        end_date_obj = datetime.datetime.strptime(end_date, "%Y-%m-%d %H:%M:%S")
                        
                        short_description = unescape(row['shortDescription'])
                        long_description = unescape(row['longDescription'])
                        full_desc = short_description
                        if long_description:
                            full_desc += ". " + long_description
                        raw_price = unescape(row['price'])
                        next_price_unit = unescape(row['priceUnit'])
                        next_price_savings = unescape(row['priceSavings'])
                        nextPromotionImageUrl = unescape(row['promotionImageUrl'])
                        nextPromotionThumbnail = unescape(row['promotionThumbnailUrl'])
                        page_number = row['pageNumber']
                        nextBonusValue = row['bonusValue']
                        nextQuantityForBonus = row['quantityForBonus']
                        nextLoyalty = row['loyalty']
                        
                        unit_price = None
                        unit_type_id = None
                        total_price = None    
                        
                        if raw_price:
                            orig_price = raw_price
                            
                            numeric_pattern = re.compile("[0-9]+")
                            numeric_only = re.compile("^[0-9.]+$")
                            
                            # If the price contains any spaces, e.g. "Starting from $19.99", then 
                            # split on space and keep the elements that contains numeric chars
                            if raw_price.find(" ") != -1:
                                raw_price = " ".join(filter(lambda x: x if numeric_pattern.findall(x) else None, raw_price.split(" ")))
                            
                            if raw_price and numeric_pattern.findall(raw_price):
                                # If a range given, take the lowest value
                                if raw_price.find("-") != -1:
                                    raw_price = raw_price.split("-")[0].strip()
                                
                                index_ratio = raw_price.find("/")
                                index_dollar = raw_price.find("$")
                                #index_cents = raw_price.find("\xa2")
                                index_cents = -1
                                #logging.info(raw_price)
                                #print("RAW PRICE: %s, index_dollar: %d" % (raw_price, index_dollar))
                                if index_ratio != -1:
                                    if numeric_only.findall(raw_price[:index_ratio]):
                                        # If the first half is numeric only, e.g. "2 / $5", then it is
                                        # indeed a ratio
                                        num_products = float(raw_price[:index_ratio])
                                        total_price = raw_price[index_ratio+1:]
                                        if index_dollar != -1:
                                            the_price = total_price.strip().strip("$").strip()
                                            if numeric_only.findall(the_price):
                                                total_price = float(the_price)
                                                # Default unit_price
                                                unit_price = total_price / num_products
                                    
                                        else:
                                            the_price = total_price.strip("\xa2").strip("\xc2")
                                            if numeric_only.findall(the_price):
                                                total_price = float(the_price) / 100.0
                                                # Default unit_price
                                                unit_price = total_price / num_products
                                    elif numeric_only.findall(raw_price[:index_ratio].strip("$").strip("\xa2").strip("\xc2")):
                                        # Otherwise, it's a range, e.g. "$33 / $36". Take the first 
                                        # price.
                                        total_price = float(raw_price[:index_ratio].strip("$").strip("\xa2").strip("\xc2"))
                                        unit_price = total_price
                                elif index_dollar != -1:
                                    the_price = raw_price.strip("$")
                                    if numeric_only.findall(the_price):
                                        total_price = float(the_price)
                                        # Default unit_price
                                        unit_price = total_price
                                elif index_cents != -1:
                                    the_price = raw_price.strip("\xa2").strip("\xc2")
                                    if numeric_only.findall(the_price):
                                        total_price = float(the_price) / 100.0
                                        # Default unit_price
                                        unit_price = total_price
                                
                                if next_price_unit:
                                    index_or = next_price_unit.find("or ")
                                    index_kg = next_price_unit.find("/kg")
                                    if index_or != -1:
                                        dollar_matches = re.search(r'(?<=[$])[0-9.]+', next_price_unit)
                                        cent_matches = re.search(r'(?<=or )[0-9.]+', next_price_unit)
                                        if dollar_matches:
                                            unit_price = float(dollar_matches.group(0))
                                        elif cent_matches:
                                            unit_price = float(cent_matches.group(0))/100.0
                                    elif index_kg != -1:
                                        price_matches = re.search(r'[0-9.]+(?=/kg)', next_price_unit)
                                        if price_matches:
                                            unit_price = float(price_matches.group(0))
                                            unit_type_id = filter(lambda x: x if x[1]=='kg' else None,units)[0][0]    
                        
                        full_desc = full_desc.replace('\r', '').replace('\n', ' ').strip()
                        if full_desc != None:
                            item_details = [stripAllTags(full_desc), stripAllTags(orig_price), unit_price, unit_type_id, total_price, \
                                            start_date, end_date, line_number, flyer_id, update_date, line_number]
                            line_number += 1
                            store_items += [item_details]
                    
                    items[flyer_id] = store_items
                except urllib2.URLError as e:
                    logging.info("Could not connect to store %d due to URLError: %s" % (store_id, str(e)))
            # Loblaws
            elif store_id == 2:
                continue
                try:
                    # Split the URL and the flyer ID info
                    arrUrl = next_url.split("|")
                    next_url = arrUrl[0]
                    province_id, city_id, storeflyer_id = arrUrl[1].split(",")
                    
                    logging.info("Crawling store: %d" % store_id)
                    parsed_url = urlparse(next_url)
                    hostname = parsed_url.hostname
                    
                    cj = cookielib.CookieJar()
                    opener = urllib2.build_opener(urllib2.HTTPCookieProcessor(cj))
                    response = opener.open(next_url).read()
                    soup = BeautifulSoup(response)
                    
                    # Fill out the form asking for Province, City, Store ID
                    the_form = soup.findAll('form')[0]
                    form_url = the_form['action'].lstrip('.').lstrip('/')
                    
                    # Get the query portion of the form URL
                    query_string = urlparse(form_url).query
                    the_url = parsed_url.scheme + "://" + hostname + "/LCL/" + "PublicationDirector.ashx?" + query_string
                    
                    post_data = []
                    
                    # input tags (hidden)
                    input_list = the_form.findChildren('input')
                    for param in input_list:
                        if param.has_key("value"):
                            post_data += [(param['id'], param['value'])]
                        else:
                            post_data += [(param['id'], '')]
                            
                    # select tags (visible)
                    post_data += [('ddlProvince', province_id), ('ddlCity', city_id), \
                                  ('ddlStore', storeflyer_id), \
                                  ('btnSelectStore', '')]
                    post_data = urllib.urlencode(post_data)
                    the_url += "&storeid=" + storeflyer_id
                    
                    response = opener.open(the_url, post_data).read()
                    soup = BeautifulSoup(response)
                    
                    # Click on the "Accessible Flyer" link to get to the actual flyer page
                    accessible_link = soup('a', text=re.compile(r'Accessible Flyer'))[0]['href']
                    accessible_link = "http://" + hostname + "/LCL/" + accessible_link
                    response = opener.open(accessible_link).read()
                    soup = BeautifulSoup(response)
                    
                    # On the last day of a flyer's period, the page may change to give the user
                    # the option of selecting either the current or next week's flyer from a 
                    # list of publications
                    flag_nopub = False
                    if soup('div', {'id': 'PublicationList'}):
                        if soup('span', {'id':'lblNoPublication'}):
                            flag_nopub = True
                        else:
                            pub_link = soup('span', {'class':'publicationDate'})[-1].parent['href']
                            pub_link = "http://" + hostname + "/LCL/" + pub_link.lstrip('.').lstrip('/')
                            response = opener.open(pub_link).read()
                            soup = BeautifulSoup(response)
                    
                    if flag_nopub:
                        logging.info("No publications for store %d this week." % store_id)
                    else:
                        # Before getting to the actual flyer page, submit an intermediate page web form
                        the_form = soup('form', {'name':'form1'})[0]
                        target_url = the_form['action']
                        children = the_form.findChildren()
                        post_data = []
                        for param in children:
                            if param.has_key("value"):
                                post_data += [(param['id'], param['value'])]
                            elif param.has_key("id"):
                                post_data += [(param['id'], '')]
                        
                        post_data = urllib.urlencode(post_data)
                        response = opener.open(target_url, post_data).read()
                        
                        # On the actual flyer page, the iframe data is populated via an AJAX call
                        # Simulate the AJAX call by fetching all necessary parameters.
                        
                        # Default values (in case not found on page):
                        BANNER_NAME = "LOB"
                        PUBLICATION_ID = "b556f81a-909c-4aa2-8f67-00f800ab9d67"
                        PUBLICATION_TYPE = "1"
                        CUSTOMER_NAME = "LCL"
                        LANGUAGE_ID = "1"
                        BANNER_ID = "3d5f3800-c099-11d9-9669-0800200c9a66"
                        
                        # Find values from the HTML:
                        # NB: Look-behind regex requires fixed-width pattern, so we can't match for arbitrary
                        # number of spaces between "=" sign and the variables..
                        match_banner = re.search(r"(?<=BANNER[_]NAME [=]['])[a-zA-Z]+(?=['])", response)
                        if match_banner:
                            BANNER_NAME = str(match_banner.group(0))
                        
                        match_pub_id = re.search(r"(?<=PUBLICATION[_]ID [=] ['])[-a-zA-Z0-9]+(?=['])", response)
                        if match_pub_id:
                            PUBLICATION_ID = str(match_pub_id.group(0))
                            
                        match_pub_type = re.search(r"(?<=PUBLICATION[_]TYPE [=] ['])[0-9]+(?=['])", response)
                        if match_pub_type:
                            PUBLICATION_TYPE = str(match_pub_type.group(0))
                        
                        match_cust_name = re.search(r"(?<=CUSTOMER[_]NAME [=] ['])[a-zA-Z]+(?=['])", response)
                        if match_cust_name:
                            CUSTOMER_NAME = str(match_cust_name.group(0))
                        
                        match_language_id = re.search(r"(?<=LANGUAGE[_]ID [=] )[0-9]+(?=[;])", response)
                        if match_language_id:
                            LANGUAGE_ID = str(match_language_id.group(0))
                        
                        match_banner_id = re.search(r"(?<=BANNER[_]ID [=] ['])[-0-9a-zA-Z]+(?=['])", response)
                        if match_banner_id:
                            BANNER_ID = str(match_banner_id.group(0))
                        
                        ajax_url = urlparse(target_url)
                        url_path = ajax_url.path[:ajax_url.path.rfind("/")+1]
                        page_id = 1
                        
                        ajax_query = ajax_url.scheme + "://" + ajax_url.netloc + url_path + \
                            "AJAXProxy.aspx?bname=" + BANNER_NAME + "&AJAXCall=GetPublicationData.aspx?" + \
                            "view=TEXT" + "&version=Flash" + "&publicationid=" + PUBLICATION_ID + \
                            "&publicationtype=" + PUBLICATION_TYPE + "&bannername=" + BANNER_NAME + \
                            "&customername=" + CUSTOMER_NAME + "&pageid1=" + str(page_id) + \
                            "&languageid=" + LANGUAGE_ID + "&bannerid=" + BANNER_ID
                        logging.info("URL: %s" % ajax_query)
                        response = opener.open(ajax_query).read()
                        dict_items = ast.literal_eval(response)
                        
                        # Parse into a list of items
                        data_list = dict_items["textdata"]
                        store_items = []
                        
                        line_number = 0
                        start_date = ""
                        end_date = ""
                        
                        # Find the start and end date
                        #Try finding the tag with "effective from" content, then do the regex
                        pattern = re.compile('effective from [^"]*["]')
                        datePattern = pattern.findall(response)
                        if datePattern:
                            response = datePattern[0]
                        [start_date, end_date] = getFlyerDates(response)
                        
                        data_list = filter(lambda x: x if x.has_key('regiontypeid') and x['regiontypeid']=='1' else None, data_list)
                        for item in data_list:
                            line_number += 1
                            raw_item = item['title'] + ", " + item['description'] 
                            
                            # Price format:
                            # 1) $1.50              (dollars)
                            # 2) 99c                (cents)
                            # 3) 4/$5               (ratio)
                            # 4) $3.99 - $4.29      (range)
                            unit_price = None
                            unit_type_id = None
                            total_price = None
                            
                            raw_price = item['price']
                            orig_price = raw_price
                            
                            numeric_pattern = re.compile("[0-9]+")
                            numeric_only = re.compile("^[0-9.]+$")
                            
                            # If the price contains any spaces, e.g. "Starting from $19.99", then 
                            # split on space and keep the elements that contains numeric chars
                            if raw_price.find(" ") != -1:
                                raw_price = " ".join(filter(lambda x: x if numeric_pattern.findall(x) else None, raw_price.split(" ")))
                            
                            if raw_price and numeric_pattern.findall(raw_price):
                                # If a range given, take the lowest value
                                if raw_price.find("-") != -1:
                                    raw_price = raw_price.split("-")[0].strip()
                                
                                index_ratio = raw_price.find("/")
                                index_dollar = raw_price.find("$")
                                index_cents = raw_price.find("\xa2")
                                #logging.info(raw_price)
                                if index_ratio != -1:
                                    if numeric_only.findall(raw_price[:index_ratio]):
                                        # If the first half is numeric only, e.g. "2 / $5", then it is
                                        # indeed a ratio
                                        num_products = float(raw_price[:index_ratio])
                                        total_price = raw_price[index_ratio+1:]
                                        if index_dollar != -1:
                                            the_price = total_price.strip().strip("$").strip()
                                            if numeric_only.findall(the_price):
                                                total_price = float(the_price)
                                                # Default unit_price
                                                unit_price = total_price / num_products
                                    
                                        else:
                                            the_price = total_price.strip("\xa2").strip("\xc2")
                                            if numeric_only.findall(the_price):
                                                total_price = float(the_price) / 100.0
                                                # Default unit_price
                                                unit_price = total_price / num_products
                                    elif numeric_only.findall(raw_price[:index_ratio].strip("$").strip("\xa2").strip("\xc2")):
                                        # Otherwise, it's a range, e.g. "$33 / $36". Take the first 
                                        # price.
                                        total_price = float(raw_price[:index_ratio].strip("$").strip("\xa2").strip("\xc2"))
                                        unit_price = total_price
                                elif index_dollar != -1:
                                    the_price = raw_price.strip("$")
                                    if numeric_only.findall(the_price):
                                        total_price = float(the_price)
                                        # Default unit_price
                                        unit_price = total_price
                                elif index_cents != -1:
                                    the_price = raw_price.strip("\xa2").strip("\xc2")
                                    if numeric_only.findall(the_price):
                                        total_price = float(the_price) / 100.0
                                        # Default unit_price
                                        unit_price = total_price
                                
                                # When price units are specified, the unit price is usually given in
                                # the "priceunits" key-value pair
                                price_units = item['priceunits']
                                if price_units:
                                    index_or = price_units.find("or ")
                                    index_kg = price_units.find("/kg")
                                    if index_or != -1:
                                        dollar_matches = re.search(r'(?<=[$])[0-9.]+', price_units)
                                        cent_matches = re.search(r'(?<=or )[0-9.]+', price_units)
                                        if dollar_matches:
                                            unit_price = float(dollar_matches.group(0))
                                        elif cent_matches:
                                            unit_price = float(cent_matches.group(0))/100.0
                                    elif index_kg != -1:
                                        price_matches = re.search(r'[0-9.]+(?=/kg)', price_units)
                                        if price_matches:
                                            unit_price = float(price_matches.group(0))
                                            unit_type_id = filter(lambda x: x if x[1]=='kg' else None,units)[0][0]
                            
                            item_details = [stripAllTags(raw_item), stripAllTags(orig_price), unit_price, unit_type_id, total_price, \
                                            start_date, end_date, line_number, flyer_id, update_date, line_number]
                            
                            store_items += [item_details]
                            
                        items[flyer_id] = store_items
                except urllib2.URLError as e:
                    logging.info("Could not connect to store %d due to URLError: %s" % (store_id, str(e)))
            # Food Basics
            elif store_id == 3:
                try:
                    logging.info("Crawling store: %d" % store_id)
                    hostname = urlparse(next_url).hostname
                    soup = BeautifulSoup(urllib2.urlopen(next_url))
                    linkElem = soup('span', text=re.compile(r'View accessible flyer'))[0].parent
                    flyer_url = "http://" + hostname + linkElem['href']
                    
                    store_items = []
                    line_number = 0
                    #logging.info("Parsing store %s, url %s" % (store_id, flyer_url))
                    
                    start_date = ""
                    end_date = ""
                    
                    soup = BeautifulSoup(urllib2.urlopen(flyer_url))
                    div_pages = soup('div')
                    
                    # Find the start and end dates
                    tag_dates = soup.find(text=re.compile('Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec[a-zA-Z]*\s[0-9]')).string
                    [start_date, end_date] = getFlyerDates(tag_dates)
                    for page in div_pages:
                        
                        page_lines = re.sub('<[bB][rR]\s*?>', '', page.text).split('\n')
                        page_lines = filter(None, map(lambda x: x.strip('\t').strip('\r').strip('\n').strip(), page_lines))
                        for line in page_lines:
                            line_number += 1
                            unit_price = 0
                            unit_type_id = None
                            total_price = None
                            
                            # Split up sentences and identify items.
                            #tokenizer = nltk.data.load('tokenizers/punkt/english.pickle')
                            #sentences = tokenizer.tokenize(line)
                            
                            # The price is preceded either by a dollar sign ($) or a cent sign (\\xa2)
                            pattern = re.compile('([\\xa2]|[$])([0-9]+[.]*[0-9]*)')
                            if pattern.findall(line):
                                total_price = pattern.findall(line)[0]
                                if total_price[0] == "$":
                                    total_price = float(total_price[1])
                                else:
                                    total_price = float(total_price[1])/100.0
                            
                            # TODO: calculate unit price by dividing if necessary
                            unit_price = total_price
                            
                            raw_item = line
                            raw_price = ""
                            tag_price = "PRICE"
                            index_price = line.find(tag_price)
                            if index_price != -1:
                                raw_price = line[index_price+len(tag_price):].strip().strip(":").strip()
                                raw_item = line[:index_price].strip()
                            
                            item_details = [stripAllTags(raw_item), stripAllTags(raw_price), unit_price, unit_type_id, total_price, \
                                            start_date, end_date, line_number, flyer_id, update_date, line_number]
                            
                            #logging.info(item_details)
                            store_items += [item_details]
                    
                    items[flyer_id] = store_items
                except urllib2.URLError as e:
                    logging.info("Could not connect to store %d due to URLError: %s" % (store_id, str(e)))
            # No Frills
            elif store_id == 4:
                try:
                    noPublication = False
                    # Split the URL and the flyer ID info
                    arrUrl = next_url.split("|")
                    next_url = arrUrl[0]
                    province_id, city_id, storeflyer_id = arrUrl[1].split(",")
                    
                    flyer_url = ""
                    logging.info("Crawling store: %d" % store_id)
                    parsed_url = urlparse(next_url)
                    hostname = parsed_url.hostname
                    
                    cj = cookielib.CookieJar()
                    opener = urllib2.build_opener(urllib2.HTTPCookieProcessor(cj))
                    response = opener.open(next_url).read()
                    soup = BeautifulSoup(response)
                    
                     # Click on the "Accessible Flyer" link to get to the actual flyer page
                    accessible_link = soup('a', text=re.compile(r'Accessible Flyer'))[0]['href']
                    accessible_link = "http://" + hostname + "/LCL/" + accessible_link
                    response = opener.open(accessible_link).read()
                    soup = BeautifulSoup(response)
                    
                    # Before getting to the actual flyer page, submit an intermediate page web form
                    parsed_accessible = urlparse(accessible_link)
                    index_end = parsed_accessible.path.rfind("/")
                    
                    # Select store
                    link_store = parsed_accessible.scheme + "://" + parsed_accessible.netloc + parsed_accessible.path[:index_end+1] +\
                                "publicationdirector.ashx?PublicationType=32&OrganizationId=797d6dd1-a19f-4f1c-882d-12d6601dc376&" +\
                                "BannerId=1f2ff19d-2888-44b3-93ea-1905aa0d9756&Language=EN&BannerName=NOFR&" +\
                                "Version=Text&pubclass=1&province=" + str(province_id) + "&city=" + str(city_id) + \
                                "&storeid=" + str(storeflyer_id)
                    response = opener.open(link_store).read()
                    soup = BeautifulSoup(response)
                    
                    # On the last day of a flyer's period, the page may change to give the user
                    # the option of selecting either the current or next week's flyer from a 
                    # list of publications
                    if soup('div', {'id': 'PublicationList'}):
                        if soup('span', {'class':'lblNoPublication'}):
                            noPublication = True
                        else:
                            pub_link = soup('span', {'class':'publicationDate'})[-1].parent['href']
                            pub_link = "http://" + hostname + "/LCL/" + pub_link.lstrip('.').lstrip('/')
                            response = opener.open(pub_link).read()
                            soup = BeautifulSoup(response)
                    
                    if not noPublication:
                        # Before getting to the actual flyer page, submit an intermediate page web form
                        the_form = soup('form', {'name':'form1'})[0]
                        target_url = the_form['action']
                        children = the_form.findChildren()
                        post_data = []
                        for param in children:
                            if param.has_key("value"):
                                post_data += [(param['id'], param['value'])]
                            elif param.has_key("id"):
                                post_data += [(param['id'], '')]
                                
                        post_data = urllib.urlencode(post_data)
                        response = opener.open(target_url, post_data).read()
                        
                        # On the actual flyer page, the iframe data is populated via an AJAX call
                        # Simulate the AJAX call by fetching all necessary parameters.
                        
                        # Default values (in case not found on page):
                        BANNER_NAME = "NOFR"
                        PUBLICATION_ID = "38706e85-01a0-4b00-94d5-25ea0cbe8eb8"
                        PUBLICATION_TYPE = "32"
                        CUSTOMER_NAME = "LCL"
                        LANGUAGE_ID = "1"
                        BANNER_ID = "1f2ff19d-2888-44b3-93ea-1905aa0d9756"
                        
                        # Find values from the HTML:
                        # NB: Look-behind regex requires fixed-width pattern, so we can't match for arbitrary
                        # number of spaces between "=" sign and the variables..
                        match_banner = re.search(r"(?<=BANNER[_]NAME [=]['])[a-zA-Z]+(?=['])", response)
                        if match_banner:
                            BANNER_NAME = str(match_banner.group(0))
                        
                        match_pub_id = re.search(r"(?<=PUBLICATION[_]ID [=] ['])[-a-zA-Z0-9]+(?=['])", response)
                        if match_pub_id:
                            PUBLICATION_ID = str(match_pub_id.group(0))
                            
                        match_pub_type = re.search(r"(?<=PUBLICATION[_]TYPE [=] ['])[0-9]+(?=['])", response)
                        if match_pub_type:
                            PUBLICATION_TYPE = str(match_pub_type.group(0))
                        
                        match_cust_name = re.search(r"(?<=CUSTOMER[_]NAME [=] ['])[a-zA-Z]+(?=['])", response)
                        if match_cust_name:
                            CUSTOMER_NAME = str(match_cust_name.group(0))
                        
                        match_language_id = re.search(r"(?<=LANGUAGE[_]ID [=] )[0-9]+(?=[;])", response)
                        if match_language_id:
                            LANGUAGE_ID = str(match_language_id.group(0))
                        
                        match_banner_id = re.search(r"(?<=BANNER[_]ID [=] ['])[-0-9a-zA-Z]+(?=['])", response)
                        if match_banner_id:
                            BANNER_ID = str(match_banner_id.group(0))
                        
                        ajax_url = urlparse(target_url)
                        url_path = ajax_url.path[:ajax_url.path.rfind("/")+1]
                        page_id = 1
                        
                        ajax_query = ajax_url.scheme + "://" + ajax_url.netloc + url_path + \
                            "AJAXProxy.aspx?bname=" + BANNER_NAME + "&AJAXCall=GetPublicationData.aspx?" + \
                            "view=TEXT" + "&version=Flash" + "&publicationid=" + PUBLICATION_ID + \
                            "&publicationtype=" + PUBLICATION_TYPE + "&bannername=" + BANNER_NAME + \
                            "&customername=" + CUSTOMER_NAME + "&pageid1=" + str(page_id) + \
                            "&languageid=" + LANGUAGE_ID + "&bannerid=" + BANNER_ID
                        logging.info("URL: %s" % ajax_query)
                        response = opener.open(ajax_query).read()
                        dict_items = ast.literal_eval(response)
                        
                        # Parse into a list of items
                        data_list = dict_items["textdata"]
                        store_items = []
                        
                        line_number = 0
                        # Find the start and end date
                        #Try finding the tag with "effective from" content, then do the regex
                        pattern = re.compile('effective from [^"]*["]')
                        datePattern = pattern.findall(response)
                        if datePattern:
                            response = datePattern[0]
                        [start_date, end_date] = getFlyerDates(response)
                        
                        data_list = filter(lambda x: x if x.has_key('regiontypeid') and x['regiontypeid']=='1' else None, data_list)
                        for item in data_list:
                            line_number += 1
                            raw_item = item['title'] + ", " + item['description'] 
                            
                            # Price format:
                            # 1) $1.50              (dollars)
                            # 2) 99c                (cents)
                            # 3) 4/$5               (ratio)
                            # 4) $3.99 - $4.29      (range)
                            unit_price = None
                            unit_type_id = None
                            total_price = None
                            
                            numeric_pattern = re.compile("[0-9]+")
                            numeric_only = re.compile("^[0-9.]+$")
                            
                            raw_price = item['price']
                            orig_price = raw_price
                            if raw_price:
                                # If a range given, take the lowest value
                                if raw_price.find("-") != -1:
                                    raw_price = raw_price.split("-")[0].strip()
                                
                                # Replace any occurrences of "for" with "/"
                                # e.g., "3 for $5" becomes "3 / $5"
                                raw_price = re.sub("\sfor\s", " / ", raw_price)
                                raw_price = raw_price.strip("*")
                                
                                index_ratio = raw_price.find("/")
                                index_dollar = raw_price.find("$")
                                index_cents = raw_price.find("\xa2")
                                if index_ratio != -1:
                                    num_products = float(raw_price[:index_ratio])
                                    total_price = raw_price[index_ratio+1:]
                                    if index_dollar != -1:
                                        total_price = float(total_price.strip().strip("$").strip())
                                    else:
                                        try:
                                            total_price = float(total_price.strip("\xa2").strip("\xc2")) / 100.0
                                        except:
                                            total_price = 0.0
                                    
                                    # Default unit_price
                                    unit_price = total_price / num_products
                                
                                elif index_dollar != -1:
                                    try:
                                        total_price = float(raw_price.strip("$").replace(',', "."))
                                    except Exception:
                                        total_price = 0.0
        
                                    # Default unit_price
                                    unit_price = total_price
                                elif index_cents != -1:
                                    if numeric_only.findall(raw_price.strip("\xa2").strip("\xc2")):
                                        
                                        total_price = float(raw_price.strip("\xa2").strip("\xc2")) / 100.0
                                    
                                        # Default unit_price
                                        unit_price = total_price
                                
                                # When price units are specified, the unit price is usually given in
                                # the "priceunits" key-value pair
                                price_units = item['priceunits']
                                if price_units:
                                    index_or = price_units.find("or ")
                                    index_kg = price_units.find("/kg")
                                    if index_or != -1:
                                        dollar_matches = re.search(r'(?<=[$])[0-9.]+', price_units)
                                        cent_matches = re.search(r'(?<=or )[0-9.]+', price_units)
                                        if dollar_matches:
                                            unit_price = float(dollar_matches.group(0))
                                        elif cent_matches:
                                            unit_price = float(cent_matches.group(0))/100.0
                                    elif index_kg != -1:
                                        price_matches = re.search(r'[0-9.]+(?=/kg)', price_units)
                                        if price_matches:
                                            unit_price = float(price_matches.group(0))
                                            unit_type_id = filter(lambda x: x if x[1]=='kg' else None,units)[0][0]
                            
                            item_details = [stripAllTags(raw_item), stripAllTags(orig_price), unit_price, unit_type_id, total_price, \
                                            start_date, end_date, line_number, flyer_id, update_date, line_number]
                            
                            store_items += [item_details]
                            
                        items[flyer_id] = store_items
                except Exception as e:
                    logging.info("Could not connect to store %d due to URLError: %s" % (store_id, str(e)))
                
            # Sobeys
            elif store_id == 5:
                #TODO pick stores
                items[flyer_id] = crawlSobeysStore(flyer_id, store_id, next_url, update_date, units)
            
            # FreshCo
            elif store_id == 6:
                items[flyer_id] = crawlFreshcoStore(flyer_id, store_id, next_url, update_date, units)
            
            logging.info('\n')
    return items


def evaluateAccuracy(store_id, labels,  category_map, item_list = None, noun_list = None):
    '''Takes a list of correct classifications, "targets", and a list of predicted classifications, "labels". 
       Returns None if either list is empty or the lists are not of the same length. Returns the fraction 
       of correctly classified items otherwise (as a floating point value between 0 and 1). '''
    
    # Read in the correct list of targets for this store's flyer
    file_in = open('flyer_' + str(store_id) + '.txt', 'rU')
    file_contents = file_in.read()
    file_in.close()
    
    if not file_contents:
        logging.info("Classification accuracy for store %d could not be determined due to missing labelled targets" % store_id)
        return None
    
    targets = map(lambda x: int(x), filter(None, file_contents.replace(os.linesep, ',').split(',')))
    '''
    if (not targets or not labels) or (len(targets) != len(labels)):
        logging.info("Classification accuracy for store %d could not be determined due to missing labelled targets" % store_id)
        return None
    '''
    if len(targets) == 0:
        logging.info("Classification accuracy for store %d could not be determined due to missing labelled targets" % store_id)
        return None
    
    correctly_classified = 0
    correctly_classified_category = 0
    for i in range(len(targets)):
        if i >= len(labels):
            logging.info("Too many targets compared to labels")
            break;
        #logging.info("(predicted: %d, actual: %d): %s" % (labels[i], targets[i], item_list[i]))
        if targets[i] == labels[i]:
            correctly_classified += 1
        elif item_list:
            logging.debug("Misclassified item (predicted: %d, actual: %d): %s" % (labels[i], targets[i], noun_list[i]))
        if category_map[targets[i]] == category_map[labels[i]]:
            correctly_classified_category += 1
    
    logging.debug("TOTAL Correctly items: %d" %correctly_classified_category)

    return [float(correctly_classified) / float(len(targets)), float(correctly_classified_category) / float(len(targets))]            

def stripAllTags(html):
    '''Cleans incoming text by removing HTML tags, and capitalizing after periods'''
    if html is None:
        return None
    html = html.replace('<br />', '. ')
    return toTitlecase((''.join(BeautifulSoup(html).findAll(text=True))), ["."])

def toTitlecase(text, chars):
    '''Custom implementation to replace Python's built-in "str.title()" because 
    the latter capitalizes after apostrophes, which is not desirable. The toTitlecase() function
    takes a list of characters, and capitalizes the first non-blank character after those 
    characters if it is an alpha character.'''
    
    ixs = [text.find(c) for c in chars]
    valid = filter(lambda k: k!=-1, ixs)
    ix = min(valid) if valid else -1
    
    while ix != -1:
        
        # Capitalize next non-whitespace character
        if ix < len(text)-1:
            nextPart = text[ix+1:]
            nextCharIx = len(nextPart) - len(nextPart.lstrip()) 
            if nextCharIx < len(nextPart):
                text = text[:ix+1] + nextPart[:nextCharIx] + \
                    nextPart[nextCharIx].upper() + nextPart[nextCharIx+1:] 
        
        # Look for next delimiter
        ixs = [text.find(c, ix+1) for c in chars]
        valid = filter(lambda k: k!=-1, ixs)
        ix = min(valid) if valid else -1
    
    return text

def getFlyerDates(tag_dates):
    '''Get the start and end day,month,year for a flyer'''
    #find month and day
    months = {'jan':1,'feb':2,'mar':3,'apr':4,'may':5,'jun':6,'jul':7,'aug':8,'sep':9,'oct':10,'nov':11,'dec':12}
    pattern = re.compile('(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-zA-Z.]*\s*([0-9]{1,2})(?=[^0-9])', re.IGNORECASE)
    matches = pattern.findall(tag_dates)
    #default values
    start_month = date.today().month
    start_date = date.today().day
    end_month = start_month
    end_date = start_date+7
    #if date information is posted in flyer, use that instead
    if matches:
        start_month = months[matches[0][0].lower()]
        start_date = int(matches[0][1])
        end_month = months[matches[1][0].lower()]
        end_date =  int(matches[1][1])

    #find year
    pattern = re.compile('201{0-9}')
    year_matches = pattern.findall(tag_dates)
    #default values
    start_year = date.today().year
    end_year = start_year+1 if end_month < start_month else start_year
    #if year information is posted in flyer, use that instead
    if year_matches: 
        start_year = int(year_matches[0])
        end_year = int(year_matches[1]) if len(year_matches) > 1 else start_year 
        
    start_date = datetime.datetime(start_year, start_month, start_date).strftime('%Y-%m-%d')
    end_date = datetime.datetime(end_year, end_month, end_date).strftime('%Y-%m-%d')
    update_date = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    logging.info("start date: %s" % start_date)
    logging.info("end date: %s" % end_date)
    logging.info("update date: %s" % update_date)
    return [start_date, end_date]

#date_str format Thu September 12-18
#return start date and start date + 6 days
def parseFlyerDates(date_str):
    #default values
    start_month = date.today().month
    start_day = date.today().day
    start_year = date.today().year
    #set day
    months = {'jan':1,'feb':2,'mar':3,'apr':4,'may':5,'jun':6,'jul':7,'aug':8,'sep':9,'oct':10,'nov':11,'dec':12}
    pattern = re.compile('(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-zA-Z.]*\s*([0-9]{1,2})', re.IGNORECASE)
    matches = pattern.findall(date_str)
    #if date information is posted in flyer, use that instead
    if matches:
        start_month = months[matches[0][0].lower()]
        start_day = int(matches[0][1])
    start_date = datetime.datetime(start_year, start_month, start_day)
    end_date = start_date + datetime.timedelta(days=6)
    start_date = start_date.strftime('%Y-%m-%d')
    end_date = end_date.strftime('%Y-%m-%d')
    return [start_date, end_date]
           
def crawlSobeysStore(flyer_id, store_id, next_url, update_date, units):
    #TODO this url is static, need to be store specifc!
    next_url = "https://www.sobeys.com/en/flyer"
    logging.info("Crawling store: %d, Flyer ID: %d, Store URL: %s"  %(store_id, flyer_id, next_url))
    try:
        soup = BeautifulSoup(urllib2.urlopen(next_url))
        store_items = []
        effective_date = soup('h3', {'class':'h3-editorial'})[0].string
        [start_date, end_date] = parseFlyerDates(effective_date)
        line_number = 1
        #loop through all the pages
        while True:
            #loop through all the items on pg
            for top_card in soup('div', {'class':'card-top'}):
                #get the useful elements
                card = top_card.find('div', {'class':'card-inset'})
                short_description = card.find('h6', {'class':'h6 x-small-bottom'}).string
                long_description = card.p.string
                amount_text = card.find('div', {'class': 'price-amount'}).getText()
                
                #set temp vars
                unit_price = None
                unit_type_id = None
                total_price = None 
                raw_price = None   
                raw_price_cents = ""
                raw_price_unit = ""
                raw_item =  short_description + '. ' + long_description
                raw_item = raw_item.replace('\r', '').replace('\n', ' ').strip()
                
                if amount_text:
                    main_amount = card.find('div', {'class':'price-amount'}).contents[0]
                    cents_sup = card.find('div', {'class': 'price-amount'}).find('sup')
                    unit_span = card.find('div', {'class':'price-amount'}).find('span')
                    price_dollar = '0'
                    price_cents = '0'
                    price_unit = '/ea.'
                    
                    if cents_sup:
                        price_cents = float(cents_sup.contents[0])
                        raw_price_cents = "." + str(cents_sup.contents[0])
                    if unit_span:
                        raw_price_unit = str(unit_span.contents[0])
                    
                    #dollar.cents case
                    if main_amount.find("/") != -1:
                        num_obj = float(main_amount.split("/")[0])
                        total_price = float(main_amount.split("/")[1]) + price_cents / 100.0
                        unit_price = total_price / num_obj
                    elif main_amount.find(".") != -1:
                        total_price = float(main_amount)
                        unit_price = total_price
                    else:
                        total_price = float(main_amount) + price_cents / 100.0
                        unit_price = total_price
                    
                    # Take care of units
                    #price_unit = '/3'
                    per_unit_match = re.search(r'([0-9]{1,2}$)', price_unit)
                    if per_unit_match:
                        try:
                            unit_price = total_price/float(per_unit_match.group(0))
                        except ValueError:
                            unit_price = 0.00
                    #/ea. /100g /lb /99c
                    elif '/lb' in price_unit:
                        unit_type_id = filter(lambda x: x if x[1]=='lb' else None,units)[0][0]
                        
                    raw_price = str(main_amount) + raw_price_cents + raw_price_unit
                    #print("ORIGINAL: %s" % (card.find('div', {'class': 'price-amount'})))
                    #print("PARSED: Total price: %s, unit price: %s, price_unit: %s" % (total_price, unit_price, str(unit_type_id))) 
                
                #add values to item
                item_details = [stripAllTags(raw_item), stripAllTags(raw_price), unit_price, unit_type_id, total_price, \
                                                start_date, end_date, line_number, flyer_id, update_date, line_number]                 
                store_items += [item_details]
                line_number += 1
                                   
            #stop if there's no more flyer pgs    
            next_pg = soup('span', {'class':'next'})
            if  next_pg is None or len(next_pg) < 1:
                break
            next_url = 'https://www.sobeys.com' + next_pg[0].a['href']
            soup = BeautifulSoup(urllib2.urlopen(next_url))
    except:
        logging.exception("Flyer parsing error!\nstoreid=%s\nurl=%s\nexception=%s\n"
                            % (store_id, next_url, sys.exc_info()))
    return store_items


def crawlFreshcoStore(flyer_id, store_id, next_url, update_date, units):
    store_items = []
    try:
        # Select specified store ID from dropdown & submit form to select as default store
        noPublication = False
        # Split the URL and the flyer ID info
        arrUrl = next_url.split("|")
        next_url = arrUrl[0]
        city_id, storeflyer_id = arrUrl[1].split(",")
        
        flyer_url = ""
        logging.info("Crawling store: %d" % store_id)
        parsed_url = urlparse(next_url)
        hostname = parsed_url.hostname
        
        cj = cookielib.CookieJar()
        opener = urllib2.build_opener(urllib2.HTTPCookieProcessor(cj))
        response = opener.open(next_url).read()
        soup = BeautifulSoup(response)
        
        # Set a cookie with store ID
        cookie_name = "FreshCo"
        cookie_val = "storeIDNew=" + storeflyer_id
        new_cookie = cookielib.Cookie(version=0, name=cookie_name, value=cookie_val, \
                    port=None, port_specified=False, domain='www.freshco.com', \
                    domain_specified=False, domain_initial_dot=False, path='/', \
                    path_specified=True, secure=False, expires=None, discard=True, \
                    comment=None, comment_url=None, rest={'HTTPOnly': None}, rfc2109=False)
        cj.set_cookie(new_cookie)
        
        # Now open the webpage
        home_url = "http://www.freshco.com/Home.aspx"
        opener = urllib2.build_opener(urllib2.HTTPCookieProcessor(cj))
        response = opener.open(home_url).read()
        soup = BeautifulSoup(response)    
        #print(soup)
        
        # Click on the "Accessible Flyer" link to get to the actual flyer page
        accessible_link = soup('a', text=re.compile(r'Accessible Flyer'))[0]['href']
        accessible_link = "http://" + hostname + accessible_link
        response = opener.open(accessible_link).read()
        soup = BeautifulSoup(response)
        
        # Submit intermediate form
        formElem = soup("form", {"name":"form1"})[0]
        target_url = formElem["action"]
        post_data = []
        
        input_list = formElem.findChildren("input")
        for param in input_list:
            if param.has_key("id") and param.has_key("value"):
                post_data += [(param["id"], param["value"])]
            elif param.has_key("id"):
                post_data += [(param["id"], "")]
        post_data = urllib.urlencode(post_data)
        response = opener.open(target_url, post_data).read()
        soup = BeautifulSoup(response)
        #print(soup)
        
        # On actual flyer page, simulate AJAX call by fetching all necessary parameters
        # Defaults
        BANNER_NAME = "FRSH"
        PUBLICATION_ID = "e279da35-ff9d-420a-a45a-1305abda1d67"
        PUBLICATION_TYPE = "2"
        CUSTOMER_NAME = "SOB"
        LANGUAGE_ID = "1"
        BANNER_ID = "731b0206-3cdf-4071-96d6-c039c8462795"
        
        # Find values from HTML
        # NB: Look-behind regex requires fixed-width pattern, so we can't match for arbitrary
        # number of spaces between "=" sign and the variables..
        match_banner = re.search(r"(?<=BANNER[_]NAME [=]['])[a-zA-Z]+(?=['])", response)
        if match_banner:
            BANNER_NAME = str(match_banner.group(0))
        
        match_pub_id = re.search(r"(?<=PUBLICATION[_]ID [=] ['])[-a-zA-Z0-9]+(?=['])", response)
        if match_pub_id:
            PUBLICATION_ID = str(match_pub_id.group(0))
            
        match_pub_type = re.search(r"(?<=PUBLICATION[_]TYPE [=] ['])[0-9]+(?=['])", response)
        if match_pub_type:
            PUBLICATION_TYPE = str(match_pub_type.group(0))
        
        match_cust_name = re.search(r"(?<=CUSTOMER[_]NAME [=] ['])[a-zA-Z]+(?=['])", response)
        if match_cust_name:
            CUSTOMER_NAME = str(match_cust_name.group(0))
        
        match_language_id = re.search(r"(?<=LANGUAGE[_]ID [=] )[0-9]+(?=[;])", response)
        if match_language_id:
            LANGUAGE_ID = str(match_language_id.group(0))
        
        match_banner_id = re.search(r"(?<=BANNER[_]ID [=] ['])[-0-9a-zA-Z]+(?=['])", response)
        if match_banner_id:
            BANNER_ID = str(match_banner_id.group(0))
        
        ajax_url = urlparse(target_url)
        url_path = ajax_url.path[:ajax_url.path.rfind("/")+1]
        page_id = 1  
        
        ajax_query = ajax_url.scheme + "://" + ajax_url.netloc + url_path + \
            "AJAXProxy.aspx?bname=" + BANNER_NAME + "&AJAXCall=GetPublicationData.aspx?" + \
            "view=TEXT" + "&version=Flash" + "&publicationid=" + PUBLICATION_ID + \
            "&publicationtype=" + PUBLICATION_TYPE + "&bannername=" + BANNER_NAME + \
            "&customername=" + CUSTOMER_NAME + "&pageid1=" + str(page_id) + \
            "&languageid=" + LANGUAGE_ID + "&bannerid=" + BANNER_ID
        logging.info("URL: %s" % ajax_query)
        response = opener.open(ajax_query).read()
        dict_items = ast.literal_eval(response)
        
        # Parse into a list of items
        data_list = dict_items["textdata"]
        store_items = []
        
        line_number = 0
        start_date = ""
        end_date = ""
        
        # Find the start and end dates
        [start_date, end_date] = getFlyerDates(response)
        
        data_list = filter(lambda x: x if x.has_key('regiontypeid') and x['regiontypeid']=='1' else None, data_list)
        for item in data_list:
            line_number += 1
            raw_item = item['title'] + ", " + item['description'] 
            
            # Price format:
            # 1) $1.50                (dollars)
            # 2) 99c                  (cents)
            # 3) 4/$5                 (ratio)
            # 4) $3.99 - $4.29        (range)
            # 5) BUY ONE GET ONE FREE (string)
            unit_price = None
            unit_type_id = None
            total_price = None
            
            raw_price = item['price']
            orig_price = raw_price
            
            numeric_pattern = re.compile("[0-9]+")
            
            # If the price contains any spaces, e.g. "Starting from $19.99", then 
            # split on space and keep the elements that contains numeric chars
            if raw_price.find(" ") != -1:
                raw_price = " ".join(filter(lambda x: x if numeric_pattern.findall(x) else None, raw_price.split(" ")))
            
            try:
                if raw_price and numeric_pattern.findall(raw_price):
                    # If a range given, take the lowest value
                    if raw_price.find("-") != -1:
                        raw_price = raw_price.split("-")[0].strip()
                    
                    index_ratio = raw_price.find("/")
                    index_dollar = raw_price.find("$")
                    index_cents = raw_price.find("\xa2")
                    if index_ratio != -1:
                        num_products = float(raw_price[:index_ratio])
                        total_price = raw_price[index_ratio+1:]
                        if index_dollar != -1:
                            total_price = float(total_price.strip().strip("$").strip())
                        else:
                            total_price = float(total_price.strip("\xa2").strip("\xc2")) / 100.0
                        
                        # Default unit_price
                        unit_price = total_price / num_products
                    
                    elif index_dollar != -1:
                        total_price = float(raw_price.strip("$"))
                        
                        # Default unit_price
                        unit_price = total_price
                        
                    elif index_cents != -1:
                        total_price = float(raw_price.strip("\xa2").strip("\xc2")) / 100.0
                        
                        # Default unit_price
                        unit_price = total_price
            except:
                total_price = 0.0
                unit_price = 0.0
            
                # When price units are specified, the unit price is usually given in
                # the "priceunits" key-value pair
                price_units = item['priceunits']
                if price_units:
                    index_or = price_units.find("or ")
                    index_kg = price_units.find("/kg")
                    if index_or != -1:
                        dollar_matches = re.search(r'(?<=[$])[0-9.]+', price_units)
                        cent_matches = re.search(r'(?<=or )[0-9.]+', price_units)
                        if dollar_matches:
                            unit_price = float(dollar_matches.group(0))
                        elif cent_matches:
                            unit_price = float(cent_matches.group(0))/100.0
                    elif index_kg != -1:
                        price_matches = re.search(r'[0-9.]+(?=/kg)', price_units)
                        if price_matches:
                            unit_price = float(price_matches.group(0))
                            unit_type_id = filter(lambda x: x if x[1]=='kg' else None,units)[0][0]
            
            item_details = [stripAllTags(raw_item), stripAllTags(orig_price), unit_price, unit_type_id, total_price, \
                            start_date, end_date, line_number, flyer_id, update_date, line_number]
            
            store_items += [item_details]
        
        #print("FRESHCO, line: ", store_items)
        
    except Exception as e:
        logging.info("Could not connect to store %d due to URLError: %s" % (store_id, str(e)))
    
    return store_items


def isSameItem(a, b):
    '''true if item a is considered the same as item b, false otherwise
        format: [raw_string, raw_price, unit_price, unit_id, total_price
                 start_date, end_date, line_number, flyer_id, update_date, score]'''
    if len(a) != len(b) or len(a) != 11: return False
    if a[0].strip().lower() == b[0].strip().lower() and a[1] == b[1] and a[2] == b[2] and a[3] == b[3] and a[4] == b[4]:  
        return True
    return False
#******************************************************************************
# An interface for accessing a database table, handles writing data to table
#******************************************************************************

class TableInterface:
    
    def __init__(self, con, table_name): 
        '''Default constructor. Takes two arguments: "con" - a valid database 
           connection to the GroceryOTG database, and "table_name" - a valid 
           string name of a database table.''' 
        
        # A list of rows of data, buffered to be inserted into database table
        self.data = []
        
        # A list of field names for the database table
        self.columns = []
        
        self.dbcon = con
        self.dbcur = con.cursor()
        self.table_name = table_name
        
        # Fetch list of columns from database
        self.dbcur.execute("DESCRIBE " + table_name)
        cols = self.dbcur.fetchall()
        
        # Keep the field names only (this contains all auto-increment fields and table primary keys)
        self.columns = [x[0] for x in cols]
        
        # The primary key column names for the table
        self.primary_key = []
        self.dbcur.execute("SHOW KEYS FROM " + self.table_name + " WHERE Key_name = 'PRIMARY'")
        res = self.dbcur.fetchall()
        if len(res) > 0:
            # Grab only the primary key column name from each entry in the results
            for key in res:
                if key:
                    # Remove the primary key from the columns list and get its index
                    key_index = self.columns.index(key[SQL_INDEX_PRIMARY_KEY])
                    self.columns.pop(key_index)
                    self.primary_key += [key_index]
        logging.debug("Created a TableInterface with primary key: %s, columns: %s" % (str(self.primary_key), self.columns))
        
    
    def add_data(self, data_list):
        '''Takes one argument, "data_list" - a list of values corresponding to one row 
           to be inserted into the table. Assumes the values are in the same order as 
           the columns in the database. Returns False if the provided list is invalid. 
           Returns True otherwise.'''
        
        if len(data_list) != len(self.columns):
            return False
        
        # Escape all the data for special characters
        data_list = map(db_escape, data_list)
        
        # NB: For blank values, you should pass in None, not NULL
        self.data += [data_list]
        return True

    def add_batch(self, data_matrix):
        '''Takes one argument, "data_matrix" - a list of lists of values, each corresponding 
           to one row to be inserted into the table. Assumes the values are in the same order as 
           the columns in the database. Returns False if the provided list is invalid. 
           Returns True otherwise.'''
        
        if not len(data_matrix) or len(data_matrix[0]) != len(self.columns):
            return False
        
        for row in data_matrix:
            row = map(db_escape, row)
            logging.debug(row)
            self.data += [row]
            
        return True

    def get_data(self):
        '''Takes no arguments. Returns the table's buffered data, as a list of lists, where each corresponds 
           to one row to be inserted into the table.'''
        return self.data
    
    def write_data(self):
        '''Takes no arguments. Writes the buffered rows of values stored in "data" to the 
           database table, if they don't already exist in the table. Returns a list of the 
           newly created row ID's (or the existing row ID's), in the order in which 
           they were created.'''
        
        logging.debug("Writing data to database table...")
        id_list = []
        
        column_str = ", ".join(self.columns)
        type_str = ", ".join(["%s"] * len(self.columns))
        where_clause = ""
        for counter in range(len(self.columns)):
            if where_clause:
                where_clause += " AND "
            where_clause += self.columns[counter] + "=%s"
        
        sql_exists = "SELECT * FROM " + self.table_name + " WHERE " + where_clause
        sql = "INSERT INTO " + self.table_name + " (" + column_str + ") VALUES (" + type_str + ")"
        formatted_data = [tuple(x) for x in self.data]
        
        for item in self.data:
            # Check if the row with these data already exists in the table
            # If it does, return the existing row ID
            cur.execute(sql_exists, item)
            res = cur.fetchall()
            if res:
                # Return the first primary key for the row
                id_list += [res[0][self.primary_key[0]]]
            # Otherwise, insert the row and return the new row ID
            else:
                cur.execute(sql, item)
                new_id = cur.lastrowid
                id_list += [new_id]
        
        #lines_inserted = self.dbcur.executemany(sql, formatted_data)
        logging.info("Wrote %d lines into table %s" %(len(id_list), self.table_name))
        
        # Commit changes to database
        self.dbcon.commit()
        
        # Clear the buffer
        self.data = []
        
        return id_list
        

# ***************************************************************************
# ***************************************************************************
con = None

try:
    con = mdb.connect(mysql_endpoint, mysql_user, mysql_password, mysql_db, use_unicode=True, charset="utf8")
    cur = con.cursor()
    logging.info("Connected to database")
    
    # Get subcategories from database
    cur.execute('SELECT subcategory_id, subcategory_tag FROM Subcategory ORDER BY subcategory_id')
    subcategory = cur.fetchall()    
    
    #Get subcategory and category IDs from the database
    cur.execute('SELECT subcategory_id, category_id FROM Subcategory;')
    id_pairs = cur.fetchall()
    category_map = {} #used in evaluateAccuracy
    for pair in id_pairs:
        category_map[pair[0]] = pair[1];
        
    # TODO: replace SQL calls with SQLAlchemy (a Python ORM)
    #logging.info("SQLAlchemy version: ", sqlalchemy.__version__)
    
    # Create an interface for writing output to the database table
    grocery_table = TableInterface(con, "Grocery")
    item_table = TableInterface(con, "Item")
    
    # Step 1: Parse the flyers into (item, price) pairs
    items = getFlyer()
    
    # Step 2: Pass the items one by one to the "getNouns" module to get a list of nouns for each item
    getNouns.init()
    logging.info('\n')
    stores = items.keys()
    for store_id in stores:
        item_list = items[store_id]
        predictions = []
        noun_table = []
        
        for item in item_list:
            
            # Only pass in the raw_item string, without the price
            noun_list = getNouns.getNouns(item[ITEM_INDEX_RAW_ITEM])
            noun_table += [noun_list]
            
            # Step 3: Pass the list of nouns to the "classifier" module to classify the item into one subcategory
            subcategory_id = classifier.classify(noun_list, subcategory)
            predictions += [subcategory_id]
            
            # Add to output buffer
            # If input from web is a <str>, convert to <unicode> for writing to database
            
            if type(item[ITEM_INDEX_RAW_ITEM]) == str:
                item_data = [item[ITEM_INDEX_RAW_ITEM].decode('utf-8')] + [subcategory_id]
            else:
                item_data = [item[ITEM_INDEX_RAW_ITEM]] + [subcategory_id]
            
            res_flag = item_table.add_data(item_data)
            if not res_flag:
                raise RuntimeError("item data could not be added to the item table handler")
            
        # Evaluate classification accuraucy for each store flyer based on hand-labelled subcategories
        #classification_rates = evaluateAccuracy(store_id, predictions, category_map, item_list, noun_table)
        #if classification_rates:
        #    logging.info("CATEGORY CLASSIFICATION RATE = %.2f for store %d" % (classification_rates[1],store_id))
        
        # Step 4: Write to Item
        item_ids = item_table.write_data()
        grocery_data = [tuple(item_ids)] + zip(*item_list)
        grocery_data = map(lambda x: list(x), zip(*grocery_data))
        
        # Encode the raw strings as UTF-8 <unicode> before adding to database, so all special 
        # characters are preserved.
        #print("GROCERY: ", type(grocery_data[0][GROCERY_INDEX_RAW_ITEM]), type(grocery_data[0][GROCERY_INDEX_ORIG_PRICE]))
        
        if len(grocery_data) > 0 and len(grocery_data[0]) > GROCERY_INDEX_ORIG_PRICE:
            if type(grocery_data[0][GROCERY_INDEX_RAW_ITEM]) == str:
                grocery_data = map(lambda x: [x[GROCERY_INDEX_ITEM_ID]] + [x[GROCERY_INDEX_RAW_ITEM].decode('utf-8')] + [x[GROCERY_INDEX_ORIG_PRICE].decode('utf-8')] + x[GROCERY_INDEX_ORIG_PRICE+1:], grocery_data)
            else:
                grocery_data = map(lambda x: [x[GROCERY_INDEX_ITEM_ID]] + [x[GROCERY_INDEX_RAW_ITEM]] + [x[GROCERY_INDEX_ORIG_PRICE]] + x[GROCERY_INDEX_ORIG_PRICE+1:], grocery_data)
            res_flag = grocery_table.add_batch(grocery_data)
            if not res_flag:
                raise RuntimeError("grocery data could not be added to the Grocery table handler")
                logging.info('\n')
    
    # Step 5: Write to Grocery
    grocery_ids = grocery_table.write_data()
    
    # Step 6: Push GCM notification
    gcm_url = r"http://groceryotg-test.appspot.com/sendAll"
    values = {"form":""}
    data = urllib.urlencode(values)
    req = urllib2.Request(gcm_url, data)
    response = urllib2.urlopen(req)
    
except Exception, e:
    exc_type, exc_obj, exc_tb = sys.exc_info()
    fname = os.path.split(exc_tb.tb_frame.f_code.co_filename)[1]
    error_msg = "Error (" + str(exc_type) + ") occurred in " + str(fname) + ", line " + str(exc_tb.tb_lineno) + ": " + str(e)
    logging.info(error_msg)
    logging.info("Traceback:")
    logging.info(traceback.format_exc())
    sendNotification(error_msg)
    sys.exit(1)
finally:
    if con:
        con.close()
        logging.info("Closed connection to database")

elapsed_time = time.time() - start_timer
logging.info("\nELAPSED: %.2f seconds" % elapsed_time)

print("done")